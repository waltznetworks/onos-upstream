package org.onosproject.drivers.cisco;

import org.onosproject.net.behaviour.LinkDiscovery;
import org.onosproject.net.driver.AbstractHandlerBehaviour;
import org.onosproject.net.link.LinkDescription;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Set;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Retrieve link descriptions from Cisco CSR1000v router through OSPF hello messages via netconf.
 */
public class LinkDiscoveryCsr1000vOspfImpl extends AbstractHandlerBehaviour
        implements LinkDiscovery {

    private final Logger log = getLogger(getClass());

    @Override
    public Set<LinkDescription> getLinks() {
        Set<LinkDescription> links = new HashSet<>();

        return links;
    }
}
