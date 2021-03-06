package com.github.ingvord.axsis;

import co.elastic.apm.api.Span;
import co.elastic.apm.api.Transaction;
import fr.esrf.Tango.DevFailed;
import fr.esrf.Tango.DevVarDoubleStringArray;
import io.reactivex.Observable;
import magix.Message;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tango.DeviceState;
import org.tango.server.annotation.*;
import org.tango.server.device.DeviceManager;
import org.tango.utils.DevFailedUtils;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;

import co.elastic.apm.api.ElasticApm;

@Device(transactionType = TransactionType.NONE)
public class AxsisController {

    private final Logger logger = LoggerFactory.getLogger(AxsisController.class);

    private String host;
    private String port;

    @DeviceManagement
    private DeviceManager manager;

    @Init
    @StateMachine(endState =  DeviceState.ON)
    public void init() throws DevFailed {
        String hostPort;
        if(manager.getName().endsWith("1")){
            hostPort = System.getProperty("CTRL1");
        } else if(manager.getName().endsWith("2")){
            hostPort = System.getProperty("CTRL2");
        } else {
            throw DevFailedUtils.newDevFailed(String.format("Invalid configuration: unknown device %s", manager.getName()));
        }

        host = hostPort.split(":")[0];
        port = hostPort.split(":")[1];
    }

    @Attribute
    public String getHost(){
        return host;
    }

    @Attribute
    public String getPort(){
        return port;
    }

    @Command(name = "Move")
    public void move(DevVarDoubleStringArray values) throws DevFailed{
        AxsisMessage message = new AxsisMessage();

        Transaction txn = ElasticApm.startTransaction();
        txn.setName("move-from-tango");
        txn.injectTraceHeaders((headerName, headerValue) -> {
            if(headerName.equalsIgnoreCase("traceparent"))
                message.withTraceparent(headerValue);
        });

        Span span = txn.startSpan();
        span.setName("axsis-tango");
        try {
            AxsisTangoServer.getMagixClient().broadcast(AxsisTangoServer.MAGIX_CHANNEL,
                    new Message<AxsisMessage>()
                            .withId(System.currentTimeMillis())
                            .withOrigin(AxsisTangoServer.MAGIX_ORIGIN)
                            .withTarget(AxsisTangoServer.MAGIX_TARGET)
                            .withPayload(
                                message
                                    .withIp(this.host)
                                    .withPort(this.port)
                                    .withAction("MOV")
                                    .withValue(
                                        Observable.zip(
                                            Observable.fromArray(values.svalue),
                                            Observable.fromArray(DoubleStream.of(values.dvalue).boxed().toArray(Double[]::new)),
                                            Map::entry
                                        ).toMap(Map.Entry::getKey, Map.Entry::getValue).blockingGet()
                                )
                            )).get();//Tango does not support async response anyway
        } catch (InterruptedException | ExecutionException e) {
            span.captureException(e);
            txn.captureException(e);
            throw DevFailedUtils.newDevFailed(e);
        } finally {
            span.end();
            txn.end();
        }
    }

    public void setManager(DeviceManager manager){
        this.manager = manager;
    }
}
