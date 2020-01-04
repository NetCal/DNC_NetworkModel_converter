package org.networkcalculus.dnc.model.converter;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.networkcalculus.dnc.curves.ArrivalCurve;
import org.networkcalculus.dnc.curves.Curve;
import org.networkcalculus.dnc.curves.ServiceCurve;
import org.networkcalculus.dnc.model.Flow;
import org.networkcalculus.dnc.model.Network;
import org.networkcalculus.dnc.model.OutPort;
import org.networkcalculus.dnc.model.can.CANBus;
import org.networkcalculus.dnc.model.can.CANController;
import org.networkcalculus.dnc.model.can.CANFrame;
import org.networkcalculus.dnc.model.can.ECANControllerType;
import org.networkcalculus.dnc.network.server_graph.Server;
import org.networkcalculus.dnc.network.server_graph.ServerGraph;

public class CANConverter {
    
    private CANConverter() {
        
    }
    
    public static final ServerGraph convert(final Network network) throws Exception {
        final ServerGraph result = new ServerGraph();
        CANBus bus = ((CANBus)network);
        List<CANFrame> frames = bus.getFrames();
        for (CANFrame frame : frames) {
            ArrivalCurve arrival_curve = createArrivalCurve(network, frames, frame);
            result.addFlow(createName(frame), arrival_curve, createPath(result, frame, bus));
        }
        return result;
    }

    private static List<Server> createPath(final ServerGraph graph, CANFrame frame, CANBus bus) {
        final List<Server> result = new ArrayList<>();
        OutPort outPort = frame.getPaths().get(0).getLinks().get(0).getSrcPort();
        ECANControllerType type = ((CANController)outPort.getPort()).getType();
        //TODO handle other controller types
        if (type == ECANControllerType.NON_PI) {
            final ServiceCurve sc = Curve.getFactory().createRateLatency(calculateRate(frame, bus), calculateLatency(frame, bus));
            Server server = graph.addServer(bus.getName(), sc);
            result.add(server);
        }
        return result;
    }

    private static String createName(CANFrame frame) {
        return frame.getName() + "(" + frame.getPriority() + ")";
    }

    private static ArrivalCurve createArrivalCurve(final Network network, final List<CANFrame> frames,final CANFrame currentFrame) {
        // b(n)+r(n)*t
        //r(n) = length/period
        final List<CANFrame> higherPrioFrames = frames.stream().filter(frame -> frame.getPriority() < currentFrame.getPriority()).collect(Collectors.toList());
        higherPrioFrames.sort(null);
        double rate = currentFrame.getMaxLenght() / currentFrame.getMinRetransmissionInterval();  
        double burst = 0.0; 
        return Curve.getFactory().createTokenBucket(rate, burst);
    }
    
    private static double calculateRate(final CANFrame currentFrame, final CANBus bus) {
        double bandwidth = bus.getBandwidth();
        double result = bandwidth;
        //Collecting higher prio VLS for delay in rate latency
        final List<Flow> higherPrioVls = bus.getFrames().stream().filter(frame -> frame.getPriority() < currentFrame.getPriority()).collect(Collectors.toList());
        if (higherPrioVls != null && higherPrioVls.size() > 0) {
            //service curve: R-rate
            // latency: frameLength/(R-rate)
            double sumRate = 0.0;
            for (final var higherPrioVl : higherPrioVls) {
                sumRate += higherPrioVl.getMaxLenght()/higherPrioVl.getMinRetransmissionInterval();
            }
            result = result - sumRate;
        }
        return result;
    }
    
    private static double calculateLatency(final CANFrame currentFrame, final CANBus bus) {
        
        //Collecting higher prio VLS for delay in rate latency
        final List<Flow> higherPrioFrames = bus.getFrames().stream().filter(frame -> frame.getPriority() < currentFrame.getPriority()).collect(Collectors.toList());
        
        double bandwidth = bus.getBandwidth();
        double frameLatency = 0.0;
        //For high prio frame: latency is port latency + one lower prio frame (with max length)
        int maxLength = 0;
        final List<Flow> lowerPrioVls = bus.getFrames().stream().filter(frame -> frame.getPriority() > currentFrame.getPriority()).collect(Collectors.toList());
        for (final var lowerPrioVl : lowerPrioVls) {
        if (lowerPrioVl.getMaxLenght() > maxLength) {
            maxLength = lowerPrioVl.getMaxLenght();
            }
        }
            
        //service curve: R-rate
        // latency: frameLength/(R-rate)
        int sumLength = maxLength;
        double sumRate = 0.0;
        for (final var higherPrioFrame : higherPrioFrames) {
            double c = higherPrioFrame.getMaxLenght()/bandwidth;
            double t = higherPrioFrame.getMinRetransmissionInterval();
            double rate = (c)/(t) * bandwidth;
            sumLength += higherPrioFrame.getMaxLenght();
            sumRate += rate;
        }
        frameLatency = sumLength / (bandwidth - sumRate);
        return frameLatency;
    }

}
