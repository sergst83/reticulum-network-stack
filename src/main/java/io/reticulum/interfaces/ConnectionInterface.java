package io.reticulum.interfaces;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.reticulum.constant.ReticulumConstant;
import io.reticulum.identity.Identity;
import io.reticulum.interfaces.auto.AutoInterface;
import io.reticulum.interfaces.backbone.BackboneClientInterface;
import io.reticulum.interfaces.backbone.BackboneServerInterface;
import io.reticulum.interfaces.tcp.TCPClientInterface;
import io.reticulum.interfaces.tcp.TCPServerInterface;
import io.reticulum.packet.Packet;
import io.reticulum.transport.AnnounceQueueEntry;
import io.reticulum.utils.IdentityUtils;

import java.time.Instant;
import java.util.Queue;

import static java.nio.charset.StandardCharsets.UTF_8;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        property = "type"
)
@JsonSubTypes({
        @Type(value = AutoInterface.class, name = "AutoInterface"),
        @Type(value = TCPClientInterface.class, name = "TCPClientInterface"),
        @Type(value = TCPServerInterface.class, name = "TCPServerInterface"),
        @Type(value = BackboneClientInterface.class, name = "BackboneClientInterface"),
        @Type(value = BackboneServerInterface.class, name = "BackboneServerInterface")
})
public interface ConnectionInterface {


    boolean OUT();
    boolean IN();
    boolean FWD();
    boolean RPT();

    default String getType() {
        return getClass().getSimpleName();
    }

    boolean isEnabled();

    Identity getIdentity();

    Integer getIfacSize();
    byte[] getIfacKey();

    default Integer getRStatRssi() {
        return null;
    }

    default Integer getRStatSnr() {
        return null;
    }

    /**
     * Quality for RNodeInterface
     *
     * @return RStatQ always returns null
     */
    default Integer getRStatQ() {return null; }

    void processIncoming(final byte[] data);
    void processOutgoing(final byte[] data);

    void setInterfaceName(String name);
    String getInterfaceName();

    /**
     * Sets the minimum amount of time, in seconds, that should pass between received announces,
     * for any one destination. As an example, setting this value to 3600 means that announces
     * received on this interface will only be re-transmitted and propagated to other interfaces
     * once every hour, no matter how often they are received
     *
     * @return seconds
     */
    Integer getAnnounceRateTarget();

    /**
     * Defines the number of times a destination can violate the announce rate before the target rate is enforced
     *
     * @return number
     */
    Integer getAnnounceRateGrace();

    /**
     * configures an extra amount of time that is added to the normal rate target.
     * As an example, if a penalty of 7200 seconds is defined, once the rate target is enforced,
     * the destination in question will only have its announces propagated every 3 hours,
     * until it lowers its actual announce rate to within the target
     *
     * @return seconds
     */
    Integer getAnnounceRatePenalty();

    InterfaceMode getMode();

    Queue<AnnounceQueueEntry> getAnnounceQueue();

    Double getAnnounceCap();
    void setAnnounceCap(double newAnnounceCap);

    Instant getAnnounceAllowedAt();
    void  setAnnounceAllowedAt(Instant announceAllowedAt);

    Integer getBitrate();

    /**
     * Preference weight for this interface when choosing between equally good
     * paths. A higher value wins. Mirrors {@code Interface.gravity}; the
     * reference default is 0.
     */
    default int getGravity() {
        return 0;
    }

    /**
     * Whether announces whose next hop is an internal-mode interface may be
     * propagated out of this interface. Mirrors
     * {@code Interface.announces_from_internal}; defaults to true.
     */
    default boolean isAnnouncesFromInternal() {
        return true;
    }

    /**
     * Whether announces arriving via this interface may be propagated into an
     * internal-mode interface. Mirrors {@code Interface.announces_to_internal};
     * null means "not configured", which is distinct from false.
     */
    default Boolean getAnnouncesToInternal() {
        return null;
    }

    /**
     * Whether a path request arriving on this interface may trigger recursive
     * path requests on other interfaces, regardless of interface mode. Mirrors
     * {@code Interface.recursive_prs}; defaults to false.
     */
    default boolean isRecursivePrs() {
        return false;
    }

    /**
     * Largest frame this interface can carry, in bytes, or {@code null} if the
     * interface declares no hardware MTU at all.
     * <p>
     * Only meaningful when {@link #isAutoconfigureMtu()} or {@link #isFixedMtu()}
     * is true; otherwise the link MTU stays at the Reticulum default. Mirrors
     * {@code Interface.HW_MTU} in the reference implementation, which is an
     * instance attribute seeded from a per-class ceiling and then recomputed by
     * {@link #optimiseMtu()} — hence nullable here too, since
     * {@code optimise_mtu()} yields {@code None} below 62.5 kbps.
     */
    default Integer getHwMtu() {
        return ReticulumConstant.MTU;
    }

    /**
     * Record a protocol violation observed on this interface.
     * <p>
     * Mirrors {@code Interface.protocol_violation()}. The reference only counts
     * and logs; the counter is what a future traffic-class implementation would
     * act on.
     */
    default void protocolViolation(String description) {
        //pass
    }

    /**
     * Recompute {@link #getHwMtu()} from the interface's bitrate.
     * <p>
     * A no-op unless {@link #isAutoconfigureMtu()} is true. Mirrors
     * {@code Interface.optimise_mtu()} ({@code RNS/Interfaces/Interface.py:250}),
     * which the reference calls after an interface is configured and after a
     * server interface spawns a client interface. Without it the per-class
     * {@code HW_MTU} is only a ceiling that is never applied: a reference TCP
     * interface at the default 10 Mbps guess ends up at 16384, not 262144.
     */
    default void optimiseMtu() {
        //pass
    }

    /**
     * The hardware MTU a given bitrate supports, or {@code null} below
     * 62.5 kbps. Table transcribed from {@code Interface.optimise_mtu()}.
     */
    static Integer optimisedMtu(Integer bitrate) {
        if (bitrate == null)              return null;
        if (bitrate >= 1_000_000_000)     return 524288;
        else if (bitrate >= 750_000_000)  return 262144;
        else if (bitrate >= 400_000_000)  return 131072;
        else if (bitrate >= 200_000_000)  return 65536;
        else if (bitrate >= 100_000_000)  return 32768;
        else if (bitrate >= 10_000_000)   return 16384;
        else if (bitrate >= 5_000_000)    return 8192;
        else if (bitrate >= 2_000_000)    return 4096;
        else if (bitrate >= 1_000_000)    return 2048;
        else if (bitrate >= 62_500)       return 1024;
        else                              return null;
    }

    /**
     * Whether links over this interface may negotiate an MTU up to
     * {@link #getHwMtu()}. Mirrors {@code Interface.AUTOCONFIGURE_MTU}.
     */
    default boolean isAutoconfigureMtu() {
        return false;
    }

    /**
     * Whether this interface always operates at {@link #getHwMtu()}. Mirrors
     * {@code Interface.FIXED_MTU}.
     */
    default boolean isFixedMtu() {
        return false;
    }

    default void detach() {
        //pass
    }

    /**
     * Returns true if the interface is currently online and able to send/receive.
     *
     * @return boolean true|false if is connected
     */
    default boolean isOnline() {
        return false;
    }

    /**
     * Returns the remote target hostname/IP for client-type interfaces, or null.
     *
     * @return target host
     */
    default String getTargetHost() {
        return null;
    }

    /**
     * Returns the remote target port for client-type interfaces, or 0.
     *
     * @return target port
     */
    default int getTargetPort() {
        return 0;
    }

    /**
     * Returns the autoconnect endpoint hash set by InterfaceDiscovery, or null.
     *
     * @return autoconnecthash bytes
     */
    default byte[] getAutoconnectHash() {
        return null;
    }

    /**
     * Sets the autoconnect endpoint hash.
     *
     * @param hash Hash to be used as a key in future connections
     */
    default void setAutoconnectHash(byte[] hash) {}

    /**
     * Returns the autoconnect source network_id hex string, or null.
     *
     * @return Source Network ID Hex
     */
    default String getAutoconnectSource() {
        return null;
    }

    /**
     * Sets the autoconnect source network_id.
     *
     * @param source Soruce of Autoconnection Endpoint
     */
    default void setAutoconnectSource(String source) {}

    /**
     * Returns the epoch-second when the auto-connected interface went offline, or null.
     *
     * @return Epoch seconds
     */
    default Long getAutoconnectDown() {
        return null;
    }

    /**
     * Sets the epoch-second when the interface went offline (null = online).
     *
     * @param downSince Time Seconds
     */
    default void setAutoconnectDown(Long downSince) {}

    default byte[] getHash() {
        return IdentityUtils.fullHash(getInterfaceName().getBytes(UTF_8));
    };

    default byte[] getTunnelId() {
        return null;
    }

    default ConnectionInterface getParentInterface() {
        return null;
    }

    default boolean isLocalSharedInstance() {
        return false;
    }

    default boolean isConnectedToSharedInstance() {
        return false;
    }

    void processAnnounceQueue();

    /**
     * Have to be threadsafe
     *
     * @param tunnelId byte array of length TUNNEL_ID_LENGTH bytes, or NULL if not a tunneled connection
     */
    default void setTunnelId(byte[] tunnelId) {}

    default boolean wantsTunnel() {
        return false;
    }

    default void setWantsTunnel(boolean wantsTunnel) {}

    /**
     * Start interface
     */
    void launch();

    void sentAnnounce(boolean fromSpawned);
    default void sentAnnounce() {
        sentAnnounce(false);
    }

    void processHeldAnnounces();

    default void receivedAnnounce() {
        receivedAnnounce(false);
    }
    void receivedAnnounce(boolean fromSpawned);

    boolean shouldIngressLimit();

    /**
     * Whether inbound path requests on this interface should currently be rate
     * limited. Mirrors {@code Interface.should_ingress_limit_pr}.
     */
    default boolean shouldIngressLimitPr() {
        return false;
    }

    /**
     * Whether an outgoing path request should be suppressed to stay within this
     * interface's egress budget. Mirrors {@code Interface.should_egress_limit_pr}.
     */
    default boolean shouldEgressLimitPr() {
        return false;
    }

    /** Records that a path request was received on this interface. */
    default void receivedPathRequest() {
    }

    /** Records that a path request was sent on this interface. */
    default void sentPathRequest() {
    }

    void holdAnnounce(Packet announcePacket);
}
