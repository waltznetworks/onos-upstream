package org.onosproject.drivers.cisco;

import com.google.common.collect.Lists;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.onlab.packet.MacAddress;
import org.onosproject.drivers.utilities.XmlConfigParser;
import org.onosproject.net.AnnotationKeys;
import org.onosproject.net.DefaultAnnotations;
import org.onosproject.net.Port;
import org.onosproject.net.PortNumber;
import org.onosproject.net.behaviour.PortDiscovery;
import org.onosproject.net.device.DefaultPortDescription;
import org.onosproject.net.device.PortDescription;
import org.onosproject.net.driver.AbstractHandlerBehaviour;
import org.onosproject.net.host.InterfaceIpAddress;
import org.onosproject.netconf.NetconfController;
import org.onosproject.netconf.NetconfException;
import org.onosproject.netconf.NetconfSession;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Retrieve port description from Cisco CSR1000v router via netconf.
 */
public class PortGetterCsr1000vImpl extends AbstractHandlerBehaviour
        implements PortDiscovery {

    private final Logger log = getLogger(getClass());

    @Override
    public List<PortDescription> getPorts() {
        NetconfController controller = checkNotNull(handler().get(NetconfController.class));
        NetconfSession session = controller.getDevicesMap().get(handler().data().deviceId()).getSession();
        String reply;
        try {
            reply = session.get(getPortsRequestBuilder());
            /* Cisco use '\r\n' as newline character, to correctly log on Linux, we need to remove '\r' */
            log.debug("Device {} replies {}", handler().data().deviceId(), reply.replaceAll("\r", ""));
        } catch (IOException e) {
            throw new RuntimeException(new NetconfException("Failed to retrieve configuration.", e));
        }

        /*
         * Interface port numbering is from 1 and up to the number of interfaces supported on CSR1000v.
         * http://www.cisco.com/c/en/us/td/docs/routers/csr1000/software/configuration/csr1000Vswcfg/csroverview.html
         *
         * So GigabitEthernet1 can be seen as port 1. First get name of the interfaces and extract their numbers.
         * Secondly, extract their speed.
         */

        List<PortDescription> descriptions =
                parseCsr1000vPorts(XmlConfigParser.loadXml(new ByteArrayInputStream(reply.getBytes())));
        return descriptions;
    }

    /**
     * Builds a request crafted to get the configuration required to create port
     * descriptions for the device.
     * @return The request string.
     */
    private String getPortsRequestBuilder() {
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
        /* Use regular expression to extract status of the interfaces */
        rpc.append("<exec>");
        rpc.append("show interfaces | include (line protocol)|(bia)|(Internet address is)|(BW [0-9]+ Kbit/sec)");
        rpc.append("</exec>");
        rpc.append("</oper-data-format-text-block>");
        rpc.append("</filter>");
        rpc.append("</get>");
        rpc.append("</rpc>");

        return rpc.toString();
    }

    /**
     * Parses a configuration and returns a set of ports for Cisco CSR1000v.
     * @param cfg a hierarchical configuration but might not in pure XML format
     * @return a list of port descriptions
     */
    private List<PortDescription> parseCsr1000vPorts(HierarchicalConfiguration cfg) {
        List<PortDescription> portDescriptions = Lists.newArrayList();
        List<Object> portNames = cfg.getList("data.cli-config-data.cmd");
        List<Object> portStatus = cfg.getList("data.cli-oper-data-block.item.response");
        int numberOfPorts = portNames.size();
        if (portStatus.size() != numberOfPorts * 5 + 1) {
            log.error("Failed to match portStatus against portName");
            return portDescriptions;
        }
        for (int i = 0; i < numberOfPorts; i++) {
            /*  Interface port numbering is from 1 on CSR1000v and up to the number of interfaces supported */
            PortNumber portNumber = PortNumber.portNumber(i + 1);
            /* Example string:
             *   GigabitEthernet1 is up, line protocol is up
             *     Hardware is CSR vNIC, address is 2cc2.6058.f7d5 (bia 2cc2.6058.f7d5)
             *     Internet address is 192.168.0.4/24
             *     MTU 1500 bytes, BW 1000000 Kbit/sec, DLY 10 usec,
             *   GigabitEthernet2 is administratively down, line protocol is down
             *     Hardware is CSR vNIC, address is 2cc2.6058.f7d6 (bia 2cc2.6058.f7d6)
             *     Internet address is 10.0.4.1/24
             *     MTU 1500 bytes, BW 1000000 Kbit/sec, DLY 10 usec,
             *
             * with default delimiter ",", List<Object> portStatus will have size numberOfPorts * 5 + 1.
             * Status of port i is in (i * 5)th cell,
             * MAC and IP address of port i are in (i * 5 + 2)th cell,
             * and port speed info of port i is in (i * 5 + 3)th cell.
             */
            boolean isEnabled = portStatus.get(i * 5).toString().contains("up");
            MacAddress macAddress = getMacAddress(portStatus.get(i * 5 + 2).toString());
            InterfaceIpAddress ipv4Address = getIpAddress(portStatus.get(i * 5 + 2).toString());
            long portSpeed = getPortSpeed(portStatus.get(i * 5 + 3).toString());

            DefaultAnnotations annotations = DefaultAnnotations.builder()
                    .set(AnnotationKeys.PORT_NAME, portNames.get(i).toString().replace("interface ", ""))
                    .set(AnnotationKeys.PORT_MAC, macAddress.toString())
                    .set(AnnotationKeys.PORT_IP, ipv4Address.toString())
                    .build();
            portDescriptions.add(new DefaultPortDescription(portNumber, isEnabled, Port.Type.COPPER, portSpeed,
                    annotations));
        }
        return portDescriptions;
    }

    private static long getPortSpeed(String str) {
        /* Example string: BW 1000000 Kbit/sec */
        return Long.parseLong(str.split("BW ")[1].split(" Kbit/sec")[0]) / 1000;
    }

    private static MacAddress getMacAddress(String str) {
        /* Example string:
         *   address is 2cc2.6058.f7d5 (bia 2cc2.6058.f7d5)
         *   Internet address is 192.168.0.4/24
         *   MTU 1500 bytes
         */
        String macPattern = "([0-9a-fA-F]{4}\\.[0-9a-fA-F]{4}\\.[0-9a-fA-F]{4})";
        Pattern pattern = Pattern.compile(macPattern);
        Matcher matcher = pattern.matcher(str);

        if (matcher.find()) {   // though there are two matches, return only the first one
            long address = Long.parseLong(matcher.group().replace(".", ""), 16);
            // remove "." and parse it as a long integer
            return MacAddress.valueOf(address);
        }
        return MacAddress.ZERO;
    }

    private static InterfaceIpAddress getIpAddress(String str) {
        /* Example string:
         *   address is 2cc2.6058.f7d5 (bia 2cc2.6058.f7d5)
         *   Internet address is 192.168.0.4/24
         *   MTU 1500 bytes
         */
        String ipv4Pattern = "(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})/(\\d{1,2})";
        Pattern pattern = Pattern.compile(ipv4Pattern);
        Matcher matcher = pattern.matcher(str);

        if (matcher.find()) {
            return InterfaceIpAddress.valueOf(matcher.group());
        }
        return InterfaceIpAddress.valueOf("0.0.0.0/0");
    }
}
