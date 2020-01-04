package org.networkcalculus.dnc.model.converter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.networkcalculus.dnc.curves.ArrivalCurve;
import org.networkcalculus.dnc.curves.Curve;
import org.networkcalculus.dnc.curves.ServiceCurve;
import org.networkcalculus.dnc.model.Flow;
import org.networkcalculus.dnc.model.Link;
import org.networkcalculus.dnc.model.Network;
import org.networkcalculus.dnc.model.OutPort;
import org.networkcalculus.dnc.model.Path;
import org.networkcalculus.dnc.model.ethernet.NetworkInterface;
import org.networkcalculus.dnc.model.ethernet.VirtualLink;
import org.networkcalculus.dnc.network.server_graph.Server;
import org.networkcalculus.dnc.network.server_graph.ServerGraph;

public class EthernetConverter {
    
    // Explanation to equations:
    // C = MaxFrameLength/Bandwidth
    // T = Frame Period sec
    // R = Bandwidth    bits/sec
    
    private final static Map<String, Server> SERVERS = new HashMap<>();
    
    private EthernetConverter() {
        
    }
    
    public static final ServerGraph convert(final Network network) throws Exception {
        //Network->Graph
        final ServerGraph result = new ServerGraph();
        for (final Flow flow : network.getFlows() ) {
            //Flow->Flow
            //TODO: Must be decided on the model what kind of arrivalCurve to use
            //FIXME: arrival curve needs correction for higher prio curves!!!
            ArrivalCurve arrival_curve = createArrivalCurve(network, flow);
            for (final Path path : flow.getPaths()) {
                result.addFlow(createName(flow, path), arrival_curve, createPath(flow, path, result, network));
            }
        }
        return result;
    }

    private static ArrivalCurve createArrivalCurve(final Network network, final Flow flow) {
        final double r = network.getBandwidth();
        final double c = flow.getMaxLenght()/r;
        final double t = flow.getMinRetransmissionInterval();
        final double rate = (c)/(t) *r;  
        final double burst = (c) * r; 
        return Curve.getFactory().createTokenBucket(rate, burst);
    }
      
    private static final String createName(final Flow flow, final Path path) {
        final String srcName = path.getLinks().get(0).getSrcPort().getPort().getDevice().getName();
        final String dstName = path.getLinks().get(path.getLinks().size()-1).getSrcPort().getPort().getDevice().getName();
        return flow.getName() + "(" + srcName + " - " + dstName + ")";
    }
    
    private static final List<Server> createPath(final Flow flow, final Path path, final ServerGraph graph, final Network network) throws Exception {
        final List<Server> result = new ArrayList<>();
        Server previousServer = null;
        for (final Link link : path.getLinks()) {
            final OutPort port = link.getSrcPort();
            //Outport -> Server
            final String portName = port.getName() + "#PRIO" + flow.getPriority();
            Server server = SERVERS.get(portName);
            if (server == null) {
                final ServiceCurve sc = Curve.getFactory().createRateLatency(calculateRate(flow, port, network), calculateLatency(flow, port, network));
                server = graph.addServer(port.getName(), sc);
                //TODO: ??show this on model level??
                server.useMaxSC(false);
                server.useMaxScRate(false);
                SERVERS.put(portName, server);
            } 
            result.add(server);
            if (previousServer != null) {
                graph.addTurn(previousServer, server);
            }
            previousServer = server;
        }
        return result;
    }

    private static double calculateRate(final Flow flow, final OutPort port, final Network network) {
        double bandwidth = network.getBandwidth();
        double result = bandwidth;
        final Set<Flow> vls = getVLsOnPort(port, network);
        //Collecting higher prio VLS for delay in rate latency
        final List<Flow> higherPrioVls = vls.stream().filter(vl -> vl.getPriority() < flow.getPriority()).collect(Collectors.toList());
        if (higherPrioVls != null && higherPrioVls.size() > 0) {
            //service curve: R-rate
            // latency: frameLength/(R-rate)
            double sumRate = 0.0;
            for (final var higherPrioVl : higherPrioVls) {
                double c = higherPrioVl.getMaxLenght()/bandwidth;
                double t = higherPrioVl.getMinRetransmissionInterval();
                double rate = (c)/(t) * bandwidth;
                sumRate += rate;
            }
            result = result - sumRate;
        }
        return result;
    }

    private static double calculateLatency(final Flow flow, final OutPort port, final Network network) {
        final Set<Flow> vls = getVLsOnPort(port, network);
        //Collecting higher prio VLS for delay in rate latency
        final List<Flow> higherPrioVls = vls.stream().filter(vl -> vl.getPriority() < flow.getPriority()).collect(Collectors.toList());
        
        double bandwidth = network.getBandwidth();
        double portLatency = port.getPort().getDevice().getInternalLatency();
        double vlLatency = 0.0;
        
        if (higherPrioVls == null || higherPrioVls.isEmpty()) {
            //For high prio frame: latency is port latency + one lower prio frame (with max length)
            int maxLength = 0;
            final List<Flow> lowerPrioVls = vls.stream().filter(vl -> vl.getPriority() > flow.getPriority()).collect(Collectors.toList());
            for (final var lowerPrioVl : lowerPrioVls) {
                if (lowerPrioVl.getMaxLenght() > maxLength) {
                    maxLength = lowerPrioVl.getMaxLenght();
                }
            }
            vlLatency = maxLength/bandwidth;
            
        } else {
            //service curve: R-rate
            // latency: frameLength/(R-rate)
            int sumLength = 0;
            double sumRate = 0.0;
            for (final var higherPrioVl : higherPrioVls) {
                double c = higherPrioVl.getMaxLenght()/bandwidth;
                double t = higherPrioVl.getMinRetransmissionInterval();
                double rate = (c)/(t) * bandwidth;
                sumLength += higherPrioVl.getMaxLenght();
                sumRate += rate;
            }
            vlLatency = sumLength / (bandwidth - sumRate);
        }
        return portLatency + vlLatency;
    }

    private static Set<Flow> getVLsOnPort(final OutPort port, final Network network) {
        final Set<Flow> result = new HashSet<>();
        if (port.getPort() instanceof NetworkInterface) {
            for (final VirtualLink vl : ((NetworkInterface)(port.getPort())).getVirtualLinks()) {
                result.add(vl);
            }
        } 
        return result;
    }
}
