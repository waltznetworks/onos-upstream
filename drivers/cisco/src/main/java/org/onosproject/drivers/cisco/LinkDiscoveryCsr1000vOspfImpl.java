package org.onosproject.drivers.cisco;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.onosproject.drivers.utilities.XmlConfigParser;
import org.onosproject.net.behaviour.LinkDiscovery;
import org.onosproject.net.driver.AbstractHandlerBehaviour;
import org.onosproject.net.link.LinkDescription;
import org.onosproject.netconf.NetconfController;
import org.onosproject.netconf.NetconfException;
import org.onosproject.netconf.NetconfSession;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.google.common.base.Preconditions.checkNotNull;
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
        List<Object> ospfLinkInfo = cfg.getList("data.cli-oper-data-block.item.response");
        for (int i = 0; i < ospfLinkInfo.size(); i++) {
            log.info("OSPF neighbor: {}", ospfLinkInfo.get(i).toString());
        }

        return links;
    }
}
