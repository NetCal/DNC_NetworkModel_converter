package org.networkcalculus.dnc.model.converter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.networkcalculus.dnc.curves.ArrivalCurve;
import org.networkcalculus.dnc.curves.Curve;
import org.networkcalculus.dnc.curves.ServiceCurve;
import org.networkcalculus.dnc.model.Flow;
import org.networkcalculus.dnc.model.Link;
import org.networkcalculus.dnc.model.Network;
import org.networkcalculus.dnc.model.OutPort;
import org.networkcalculus.dnc.model.Path;
import org.networkcalculus.dnc.network.server_graph.Server;
import org.networkcalculus.dnc.network.server_graph.ServerGraph;

public class Converter {
    
    // Explanation to equations:
    // C = MaxFrameLength/Bandwidth
    // T = Frame Period
    // R = Bandwidth
    
    private final static Map<String, Server> SERVERS = new HashMap<>();
    
    public static final ServerGraph convert(final Network network) throws Exception {
        //Network->Graph
        final ServerGraph result = new ServerGraph();
        for (final Flow flow : network.getFlows() ) {
            //Flow->Flow
            final double r = network.getBandwidth();
            final double c = flow.getMaxLenght()/r;
            final double t = flow.getMinRetransmissionInterval();
            final double rate = (c)/(t) *r;  
            final double burst = (c) * r; 
            ArrivalCurve arrival_curve = Curve.getFactory().createTokenBucket(rate, burst);
            for (final Path path : flow.getPaths()) {
                result.addFlow(createName(flow), arrival_curve, createPath(path, result, network.getBandwidth()));
            }
        }
        return result;
    }
      
    private static final String createName(final Flow flow) {
        return flow.getName();
    }
    
    private static final List<Server> createPath(final Path path, final ServerGraph graph, final double bandwidth) throws Exception {
        final List<Server> result = new ArrayList<>();
        Server previousServer = null;
        for (final Link link : path.getLinks()) {
            final OutPort port = link.getSrcPort();
            //Outport -> Server
            Server server = SERVERS.get(port.getName());
            if (server == null) {
                final ServiceCurve sc = Curve.getFactory().createRateLatency(bandwidth, port.getPort().getDevice().getInternalLatency());
                server = graph.addServer(port.getName(), sc);
                //TODO: ??show this on model level??
                server.useMaxSC(false);
                server.useMaxScRate(false);
                SERVERS.put(port.getName(), server);
            } 
            result.add(server);
            if (previousServer != null) {
                graph.addTurn(previousServer, server);
            }
            previousServer = server;
        }
        return result;
    }
    
}
