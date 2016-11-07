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
        String[] array;
        for (int i = 0; i < ospfLinkInfo.size(); i++) {
            log.debug("OSPF neighbor: {}", ospfLinkInfo.get(i).toString());
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
            array = ospfLinkInfo.get(i).toString().split(" +");                 // split with multiple spaces
            if (!array[2].contains("FULL") || array.length != 6) {
                continue;   // skip if there is no bi-directional link
            }

            remoteDeviceId = getDeviceId(array[0]);
            if (remoteDeviceId == null) {
                continue;
            }

            remotePortNumber = getPortNumber(remoteDeviceId, array[4]);
            if (remotePortNumber.equals(PortNumber.ANY)) {
                log.warn("Cannot resolve port number of IP:{} on router:{}", array[4], array[0]);
            }

            localPortNumber = PortNumber.portNumber(Long.parseLong(array[5].replace("GigabitEthernet", "")));

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

        try {
            deviceId = DeviceId.deviceId(new URI("netconf", ip + ":" + 22, null));      // check if port is 22
            if (deviceService.getDevice(deviceId) != null) {
                return deviceId;
            }
            deviceId = DeviceId.deviceId(new URI("netconf", ip + ":" + 830, null));     // check if port is 830
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
