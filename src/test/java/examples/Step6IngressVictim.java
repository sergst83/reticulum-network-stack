package examples;

import io.reticulum.Reticulum;
import io.reticulum.Transport;
import io.reticulum.interfaces.AbstractConnectionInterface;

import java.nio.file.Path;

/**
 * Step 6 victim: a Java node that reports, once a second, how much of an
 * announce flood it has actually absorbed.
 * <p>
 * The three numbers are the whole test. {@code known} is the path-table size —
 * announces that were let through. {@code held} is what ingress control is
 * sitting on. {@code burst} is whether the limiter is engaged. A run that never
 * engages, never holds, or never drains is a broken limiter, and each shows up
 * as a different shape in this series.
 * <p>
 * Usage: {@code Step6IngressVictim <config-dir> <seconds>}
 */
public class Step6IngressVictim {

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: Step6IngressVictim <config-dir> <seconds>");
            System.exit(2);
        }

        new Reticulum(Path.of(args[0]).toAbsolutePath().toString());
        var seconds = Integer.parseInt(args[1]);
        var transport = Transport.getInstance();

        System.out.println("[step6-victim] ready");
        System.out.flush();

        for (var t = 0; t < seconds; t++) {
            var known = transport.getDestinationTable().size();

            var held = 0;
            var burst = false;
            for (var iface : transport.getInterfaces()) {
                if (iface instanceof AbstractConnectionInterface) {
                    var aci = (AbstractConnectionInterface) iface;
                    held += aci.getHeldAnnounces().size();
                    burst |= aci.isIcBurstActive();
                }
            }

            System.out.println("[step6-victim] t=" + t + " known=" + known + " held=" + held
                    + " burst=" + burst);
            System.out.flush();
            Thread.sleep(1000);
        }

        System.out.println("[step6-victim] done");
        System.out.flush();
        System.exit(0);
    }
}
