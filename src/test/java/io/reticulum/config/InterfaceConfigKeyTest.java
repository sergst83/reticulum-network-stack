package io.reticulum.config;

import io.reticulum.interfaces.ConnectionInterface;
import io.reticulum.interfaces.InterfaceMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Config keys that reach an interface only through {@code @JsonAlias}.
 * <p>
 * The interface hierarchy uses explicit-only {@code @JsonAutoDetect}, so a field
 * is invisible to Jackson unless something marks it as a property.
 * {@code @JsonAlias} supplies alternative names for a property that is already
 * discoverable — on its own it does not make one discoverable. These keys have
 * no other annotation, so this pins down whether they actually bind.
 */
class InterfaceConfigKeyTest {

    private static ConnectionInterface loadSingleInterface(String yaml) throws Exception {
        var dir = Files.createTempDirectory("iface-config-test");
        var file = dir.resolve("config.yml");
        Files.writeString(file, yaml);

        var config = ConfigObj.initConfig(file);
        var interfaces = config.getInterfaces();
        assertNotNull(interfaces, "no interfaces parsed");
        assertEquals(1, interfaces.size());

        return interfaces.values().iterator().next();
    }

    @Test
    @DisplayName("an interface's mode can be set from the config")
    void modeBindsFromConfig() throws Exception {
        var iface = loadSingleInterface(
                    "reticulum:\n"
                  + "  enable_transport: true\n"
                  + "interfaces:\n"
                  + "  \"Test Server\":\n"
                  + "    type: TCPServerInterface\n"
                  + "    enabled: true\n"
                  + "    listen_ip: 127.0.0.1\n"
                  + "    listen_port: 45999\n"
                  + "    mode: gateway\n");

        assertEquals(InterfaceMode.MODE_GATEWAY, iface.getMode(),
                "mode: gateway must reach the interface — without it a node can never be "
                        + "configured as a gateway, access point, boundary or roaming peer");
    }

    @Test
    @DisplayName("the interface_mode spelling binds too")
    void interfaceModeSpellingBinds() throws Exception {
        var iface = loadSingleInterface(
                    "reticulum:\n"
                  + "  enable_transport: true\n"
                  + "interfaces:\n"
                  + "  \"Test Server\":\n"
                  + "    type: TCPServerInterface\n"
                  + "    enabled: true\n"
                  + "    listen_ip: 127.0.0.1\n"
                  + "    listen_port: 45999\n"
                  + "    interface_mode: accesspoint\n");

        assertEquals(InterfaceMode.MODE_ACCESS_POINT, iface.getMode());
    }

    @Test
    @DisplayName("IFAC network name and passphrase bind from the config")
    void ifacKeysBindFromConfig() throws Exception {
        var iface = loadSingleInterface(
                    "reticulum:\n"
                  + "  enable_transport: true\n"
                  + "interfaces:\n"
                  + "  \"Test Server\":\n"
                  + "    type: TCPServerInterface\n"
                  + "    enabled: true\n"
                  + "    listen_ip: 127.0.0.1\n"
                  + "    listen_port: 45999\n"
                  + "    networkname: testnet\n"
                  + "    passphrase: hunter2\n");

        var abstractIface = (io.reticulum.interfaces.AbstractConnectionInterface) iface;
        assertEquals("testnet", abstractIface.getIfacNetName());
        assertEquals("hunter2", abstractIface.getIfacNetKey());
    }

    @Test
    @DisplayName("a configured passphrase actually engages IFAC")
    void passphraseEngagesIfac() throws Exception {
        // Binding the field is only half of it. initIFac() gates on
        // ifacNetName/ifacNetKey being non-blank and otherwise returns having done
        // nothing at all — no key, no signature, no warning. So a passphrase that
        // did not bind left the interface unauthenticated and silent about it.
        var iface = (io.reticulum.interfaces.AbstractConnectionInterface) loadSingleInterface(
                    "reticulum:\n"
                  + "  enable_transport: true\n"
                  + "interfaces:\n"
                  + "  \"Test Server\":\n"
                  + "    type: TCPServerInterface\n"
                  + "    enabled: true\n"
                  + "    listen_ip: 127.0.0.1\n"
                  + "    listen_port: 45999\n"
                  + "    passphrase: hunter2\n");

        assertTrue(io.reticulum.utils.InterfaceUtils.initIFac(iface));
        assertNotNull(iface.getIfacKey(), "IFAC key must be derived from the passphrase");
        assertEquals(64, iface.getIfacKey().length);
        assertNotNull(iface.getIdentity(), "IFAC identity must exist");
        assertNotNull(iface.getIfacSignature(), "IFAC signature must exist");
    }
}
