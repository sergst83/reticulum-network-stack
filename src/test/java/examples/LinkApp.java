package examples;

import io.reticulum.Reticulum;
import io.reticulum.Transport;
import io.reticulum.destination.Destination;
import io.reticulum.destination.DestinationType;
import io.reticulum.destination.Direction;
import io.reticulum.identity.Identity;
import io.reticulum.link.Link;
import io.reticulum.constant.LinkConstant;
import io.reticulum.packet.Packet;
import static io.reticulum.link.TeardownSession.DESTINATION_CLOSED;
//import static io.reticulum.link.TeardownSession.INITIATOR_CLOSED;
import static io.reticulum.link.TeardownSession.TIMEOUT;
import static io.reticulum.identity.IdentityKnownDestination.recall;
import static io.reticulum.constant.ReticulumConstant.TRUNCATED_HASHLENGTH;

//import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.nonNull;
//import static org.apache.commons.lang3.BooleanUtils.isFalse;
//import static org.mockito.ArgumentMatchers.isNull;

import java.util.concurrent.TimeUnit;

//import static org.apache.commons.codec.binary.Hex.encodeHexString;
//import static org.apache.commons.codec.binary.Hex.decodeHex;
//import org.bouncycastle.util.encoders.UTF8;

@Getter
@Slf4j
public class LinkApp {
    static final String APP_NAME = "example_utilities";
    private Reticulum reticulum;
    String configPath;
    Identity serverIdentity;
    private Destination baseDestination;  // server
    private Destination serverDestination; // client
    private Link serverLink;
    private Link clientLink;
    private Link latestClientLink;
    private Transport transportInstance;

    public LinkApp (String configPathArg) {
        // config paths for testing are src/test/resources/{node1,node2}
        configPath = Path.of(configPathArg).toAbsolutePath().toString();

        init();
    }

    public void init () {
        try {
            reticulum = new Reticulum(this.configPath);
        } catch (IOException e) {
            log.error("unable to create Reticulum network", e);
        }
        transportInstance = Transport.getInstance();
    }

    public void shutdown () {
        if (nonNull(serverLink)) {
            serverLink.teardown();
        }
        else if (nonNull(clientLink)) {
            clientLink.teardown();
        }
        reticulum.exitHandler();
    }
    
    /************/
    /** Server **/
    /************/
    public void serverSetup() {

        // create identity either from file or new (creating new keys)
        var serverIdentityPath = reticulum.getStoragePath().resolve("identities/"+APP_NAME);
        if (Files.isReadable(serverIdentityPath)) {
            serverIdentity = Identity.fromFile(serverIdentityPath);
            log.info("server identity loaded from file {}", serverIdentityPath.toString());
        } else {
            serverIdentity = new Identity();
            log.info("new server identity created dynamically.");
        }

        // We create a destination that clients can connect to. We
        // want clients to create links to this destination, so we
        // need to create a "single" destination type.
        baseDestination = new Destination(
            serverIdentity,
            Direction.IN,
            DestinationType.SINGLE,
            APP_NAME,
            "linkexample"
        );

        // We configure a function that will get called every time
        // a new client creates a link to this destination.
        baseDestination.setLinkEstablishedCallback(this::clientConnected);
    }

    public void clientConnected(Link link) {
        link.setLinkClosedCallback(this::clientDisconnected);
        link.setPacketCallback(this::serverPacketReceived);
        latestClientLink = link;
        log.info("***> Client connected");
    }

    public void clientDisconnected(Link link) {
        log.info("***> Client disconnected");
    }

    public void serverPacketReceived(byte[] message, Packet packet) {
        String text = new String(message, StandardCharsets.UTF_8);
        log.info("Received data on the link: \"{}\"", text);
        // send reply
        String replyText = "I received \""+text+"\" over the link";
        byte[] replyData = replyText.getBytes(StandardCharsets.UTF_8);
        Packet reply = new Packet(clientLink, replyData);
        reply.send();
    }

    /************/
    /** Client **/
    /************/
    public void clientSetup(byte[] destinationHash) {
        Integer destLen = (TRUNCATED_HASHLENGTH / 8) * 2;  // hex characsters
        if (Math.floorDiv(destLen, 2) != destinationHash.length) {
            log.info("Destination length ({} byte) is invalid, must be {} (hex) hexadecimal characters ({} bytes)", destinationHash.length, destLen, Math.floorDiv(destLen,2));
            throw new IllegalArgumentException("Destination length is invalid");
        }

        transportInstance = Transport.getInstance();
        //// Check if we know the destination
        //var probeCount = 0;
        //var maxProbeCount = 10;
        //if (isFalse(Transport.getInstance().hasPath(destinationHash))) {
        //    log.info("Destination is not yet known. Requesting path and waiting for announce to arrive...");
        //    Transport.getInstance().requestPath(destinationHash);
        //    //while (isFalse(Transport.getInstance().hasPath(destinationHash))) {
        //    while (isFalse(Transport.getInstance().hasPath(destinationHash)) && (probeCount < maxProbeCount)) {
        //        log 
        //        try {
        //            TimeUnit.MILLISECONDS.sleep(500);
        //        } catch (InterruptedException e) {
        //            log.info("sleep interrupted: {}", e);
        //        }
        //    }
        //}
        //log.info("Transport hasPath to {} - {}",
        //    encodeHexString(destinationHash),
        //    Transport.getInstance().hasPath(destinationHash));

        // Recall server identity and inform user that we'll begin connecting
        serverIdentity = recall(destinationHash);
        log.debug("client - serverIdentity: {}", serverIdentity);

        log.info("Establishing link with server...");

        // When the server identity is known, we set up a destination
        serverDestination = new Destination(
            serverIdentity,
            Direction.OUT,
            DestinationType.SINGLE,
            APP_NAME,
            "linkexample"
        );

        // And create a link
        clientLink = new Link(serverDestination);
        //latestClientLink = clientLink;

        // We set a callback that will be executed
        // every time a packet is received over the link
        clientLink.setPacketCallback(this::clientPacketReceived);

        // We'll also set up functions to inform the user
        // when the link is established or closed
        clientLink.setLinkEstablishedCallback(this::linkEstablished);
        clientLink.setLinkClosedCallback(this::linkClosed); 
    }

    public void sendTestPacket (String msg) {
        try {
            var data = "hello test".getBytes(UTF_8);
            if (data.length <= LinkConstant.MDU) {
                Packet testPacket = new Packet(serverLink, data);
                testPacket.send();
            } else {
                log.info("Cannot send this packet, the data length of {} bytes exceeds link MDU of {} bytes", data.length, LinkConstant.MDU);
            }
        } catch (Exception e) {
            log.error("Error sending data over the link: {}", e);
            serverLink.teardown();
        }

    }

    public void linkEstablished(Link link) {
        // We store a reference to the link instance for later use
        this.serverLink = link;

        // INform the user that the server is connected
        //log.info("Link established with server, enter some text to send, or \"quit\" to quit");
    }

    public void linkClosed(Link link) {
        if (link.getTeardownReason() == TIMEOUT) {
            log.info("The link timed out, exiting now");
        } else if (link.getTeardownReason() == DESTINATION_CLOSED) {
            log.info("Link closed callback: The link was closed by the server.");
        } else {
            log.info("Link closed callback.");
        }

        reticulum.exitHandler();
        try {
            TimeUnit.MILLISECONDS.sleep(1500);
        } catch (InterruptedException e) {
            log.info("sleep interrupted: {}", e);
        }
    }

    public void clientPacketReceived(byte[] message, Packet packet) {
        String text = new String(message, StandardCharsets.UTF_8);
        log.info("Received data on the link: {}", text);
        //System.out.print("> ");
    }

}
