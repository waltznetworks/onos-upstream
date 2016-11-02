package org.onosproject.drivers.cisco;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.onosproject.drivers.utilities.XmlConfigParser;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Link;
import org.onosproject.net.Port;
import org.onosproject.net.PortNumber;
import org.onosproject.net.behaviour.LinkDiscovery;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.driver.AbstractHandlerBehaviour;
import org.onosproject.net.link.DefaultLinkDescription;
import org.onosproject.net.link.LinkDescription;
import org.onosproject.netconf.NetconfController;
import org.onosproject.netconf.NetconfException;
import org.onosproject.netconf.NetconfSession;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.onosproject.net.AnnotationKeys.PORT_IP;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Retrieve link descriptions from Cisco CSR1000v router through OSPF hello messages via netconf.
 */
public class LinkDiscoveryCsr1000vOspfImpl extends AbstractHandlerBehaviour
        implements LinkDiscovery {

    private final Logger log = getLogger(getClass());
    private static final int SSH_PORT = 22;
    private static final int NETCONF_PORT = 830;

    @Override
    public Set<LinkDescription> getLinks() {
        NetconfController controller = checkNotNull(handler().get(NetconfController.class));
        NetconfSession session = controller.getDevicesMap().get(handler().data().deviceId()).getSession();
        String reply;
        try {
            reply = session.get(getOspfLinksRequestBuilder());
            log.debug("Device {} replies {}", handler().data().deviceId(), reply.replaceAll("\r", ""));
        } catch (IOException e) {
            throw new RuntimeException(new NetconfException("Failed to retrieve configuration.", e));
        }

        Set<LinkDescription> links =
                parseCsr1000vOspfLinks(XmlConfigParser.loadXml(new ByteArrayInputStream(reply.getBytes())));

        return links;
    }

    /**
     * Builds a request crafted to get the configuration required to create link
     * descriptions for the device.
     * @return The request string.
     */
    private String getOspfLinksRequestBuilder() {
        StringBuilder rpc = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        //Message ID is injected later.
        rpc.append("<rpc xmlns=\"urn:ietf:params:xml:ns:netconf:base:1.0\">");
        rpc.append("<get>");
        rpc.append("<filter>");
        rpc.append("<config-format-text-cmd>");
        /* Use regular expression to extract only name of the interfaces from running-config */
        rpc.append("<text-filter-spec> | include interface </text-filter-spec>");
        rpc.append("</config-format-text-cmd>");
        rpc.append("<oper-data-format-text-block>");
        /* Use regular expression to extract status of the interfaces
         * Note that there is an space after 'packet'
         */
        rpc.append("<exec>show ip ospf neighbor | include Ethernet</exec>");
        rpc.append("</oper-data-format-text-block>");
        rpc.append("</filter>");
        rpc.append("</get>");
        rpc.append("</rpc>");

        return rpc.toString();
    }

    /**
     * Parses a configuration and returns a set of link descriptions for Cisco CSR1000v.
     * @param cfg a hierarchical configuration but might not in pure XML format
     * @return a set of link descriptions
     */
    private Set<LinkDescription> parseCsr1000vOspfLinks(HierarchicalConfiguration cfg) {
        Set<LinkDescription> links = new HashSet<>();

        DeviceId localDeviceId = handler().data().deviceId();   // Must be a NETCONF device
        DeviceId remoteDeviceId;                                // Could be an OpenFlow, SNMP, or NETCONF device
        PortNumber localPortNumber;
        PortNumber remotePortNumber;

        List<Object> ospfLinkInfo = cfg.getList("data.cli-oper-data-block.item.response");
        if (ospfLinkInfo.size() >= 2) {
            log.error("Replied message contains multiple <response> tags");
        }
        log.debug("OSPF neighbor: {}", ospfLinkInfo.get(0).toString());

        /* Example string:
         *   192.168.0.3       1   DOWN            00:00:00    10.100.2.3      GigabitEthernet3
         *   192.168.0.4       1   FULL/DR         00:00:31    10.100.4.4      GigabitEthernet4
         *
         * The first column is the router ID, which Cisco by default use the addresses set on GigabitEthernet1;
         * the second column is the priority and we don't use it for link discovery; the third one is the status
         * of the link. As long as it contains "FULL", we can safely assume there is bi-directional full-speed
         * link; the fourth columns is the IP address set on the interface on the neighbor, we will need to use
         * this to find out port number later; the fifth column is the local interface of the link and we should
         * convert it to local port number. Refer to:
         * http://www.cisco.com/c/en/us/support/docs/ip/open-shortest-path-first-ospf/13688-16.html
         * for more detail.
         */

        String[] entries = ospfLinkInfo.get(0).toString().split("\n");          // split with newline characters

        String[] columns;
        for (int i = 0; i < entries.length; i++) {
            columns = entries[i].split(" +");                                   // split with multiple spaces

            if (columns.length != 6) {
                log.debug("Entry {} has wrong format, skipping...", entries[i]);
                continue;   // skip if the entry has wrong format
            }

            if (!columns[2].contains("FULL")) {
                continue;   // skip if there is no bi-directional link
            }

            remoteDeviceId = getDeviceId(columns[0]);
            if (remoteDeviceId == null) {
                log.warn("Cannot resolve ID of remote device {}, skipping...", columns[0]);
                continue;
            }

            remotePortNumber = getPortNumber(remoteDeviceId, columns[4]);
            if (remotePortNumber.equals(PortNumber.ANY)) {
                log.warn("Cannot resolve port number of IP:{} on router:{}", columns[4], columns[0]);
            }

            localPortNumber = PortNumber.portNumber(Long.parseLong(columns[5].replace("GigabitEthernet", "")));

            links.add(new DefaultLinkDescription(
                    new ConnectPoint(localDeviceId, localPortNumber),
                    new ConnectPoint(remoteDeviceId, remotePortNumber),
                    Link.Type.DIRECT));
        }

        return links;
    }

    /**
     * Use OSPF router ID (IP address) to resolve device ID.
     */
    private DeviceId getDeviceId(String ip) {
        DeviceService deviceService = checkNotNull(handler().get(DeviceService.class));
        DeviceId deviceId;

        /* The remote device could be an OpenFlow device, an SNMP devices, or a NETCONF device. In ONOS,
         * these devices will have different URI prefix. For example, an OpenFlow device will have 'of' prefix
         * and no port number; an SNMP device, similarly, will have 'snmp' prefix; a NETCONF device will have
         * 'netconf' prefix and so on. This method should serve as a DeviceId resolver. That is, given an IP,
         * try to look for the device in ONOS and return its DeviceId.
         */
        try {
            /* Test if the remote device is a NETCONF device. NETCONF protocol runs on either port 22 on Cisco
             * routers through SSHv2 tunnels or port 830 on Juniper routers through non-encrypted tunnels.
             */
            deviceId = DeviceId.deviceId(new URI("netconf", ip + ":" + SSH_PORT, null));      // check if port is 22
            if (deviceService.getDevice(deviceId) != null) {
                return deviceId;
            }
            deviceId = DeviceId.deviceId(new URI("netconf", ip + ":" + NETCONF_PORT, null));     // check if port is 830
            if (deviceService.getDevice(deviceId) != null) {
                return deviceId;
            }
            // TODO: add support of SNMP and OpenFlow devices
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Unable to resolve deviceID for IP:" + ip, e);
        }
        return null;
    }

    /**
     * Use port IP address to resolve port number. We need port IP address to be added to port annotation dict
     * in advance.
     */
    private PortNumber getPortNumber(DeviceId deviceId, String portIp) {
        DeviceService deviceService = checkNotNull(handler().get(DeviceService.class));
        List<Port> ports = deviceService.getPorts(deviceId);
        for (Port port : ports) {
            log.debug("{} port {} has IP:{}", deviceId, port.number(), port.annotations().value(PORT_IP).toString());
            if (port.annotations().value(PORT_IP).contains(portIp)) {
                return port.number();
            }
        }
        return PortNumber.ANY;
    }
}
