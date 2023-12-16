package io.reticulum.interfaces.RNode;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fazecast.jSerialComm.SerialPort;
import io.reticulum.interfaces.AbstractConnectionInterface;
import lombok.Setter;

import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicBoolean;

public class RNodeInterface extends AbstractConnectionInterface implements RNodeInterfaceUtil {

    private static final int HW_MTU = 508;
    private static final int speed = 115200;
    private static final int databits = 8;
    private static final int stopbits = 1;

    private long timeout; //ms
    private AtomicBoolean detached = new AtomicBoolean();
    private AtomicBoolean reconnecting = new AtomicBoolean();
    private SerialPort serial;

    @Setter
    @JsonProperty("port")
    private String port;
    @Setter
    @JsonProperty("frequency")
    private Integer frequency;
    @Setter
    @JsonProperty("bandwidth")
    private Integer bandwidth;
    @Setter
    @JsonProperty("txpower")
    private Integer txPower;
    @Setter
    @JsonProperty("spreadingfactor")
    private Integer sf;
    @Setter
    @JsonProperty("codingrate")
    private Integer cr;
    @Setter
    @JsonProperty("flow_control")
    private Boolean flowControl;
    @Setter
    @JsonProperty("id_interval")
    private Integer idInterval;
    @Setter
    @JsonProperty("id_callsign")
    private Integer idCallsign;

    public RNodeInterface() {
        rxb.set(BigInteger.ZERO);
        txb.set(BigInteger.ZERO);

        timeout = 100;

        online.set(false);
        detached.set(false);
        reconnecting.set(false);
    }

    @Override
    public void processIncoming(byte[] data) {

    }

    @Override
    public void processOutgoing(byte[] data) {

    }

    @Override
    public void launch() {

    }
}
