package io.reticulum.utils;

import io.reticulum.identity.Identity;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.io.IOException;
import java.util.StringJoiner;

import static io.reticulum.constant.IdentityConstant.NAME_HASH_LENGTH;
import static io.reticulum.constant.ReticulumConstant.TRUNCATED_HASHLENGTH;
import static io.reticulum.utils.IdentityUtils.concatArrays;
import static io.reticulum.utils.IdentityUtils.fullHash;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.ArrayUtils.subarray;

public class DestinationUtils {

    /**
     * Full human-readable name of the destination
     *
     * @param identity {@link Identity}
     * @param appName {@link String}
     * @param aspects {@link String}
     * @return A string containing the full human-readable name of the destination, for an app_name and a number of aspects
     */
    public static String expandName(Identity identity, @NonNull String appName, String... aspects) {
        if (StringUtils.contains(appName, '.')) {
            throw new IllegalArgumentException("Dots can't be used in app names");
        }

        var nameJoiner = new StringJoiner(".").add(appName);
        for (String aspect : aspects) {
            if (StringUtils.contains(aspect, '.')) {
                throw new IllegalArgumentException("Dots can't be used in aspects");
            }
            nameJoiner.add(aspect);
        }

        if (nonNull(identity)) {
            nameJoiner.add(identity.getHexHash());
        }

        return nameJoiner.toString();
    }

    /**
     * @param fullName {@link String}
     * @return An unmodifiable containing the app name and a list of aspects, for a full-name string.
     */
    public static Pair<String, String[]> appAndAspectsFromName(@NonNull String fullName) {
        var array = StringUtils.split(fullName, '.');

        return Pair.of(array[0], subarray(array, 1, array.length));
    }

    /**
     * @return A destination name in adressable hash form, for a full name string and Identity instance
     */
    public static byte[] hashFromNameAndIdentity(@NonNull String fullName, Identity identity) throws IOException {
        var appAndAspects = appAndAspectsFromName(fullName);

        return hash(identity, appAndAspects.getLeft(), appAndAspects.getRight());
    }

    /**
     * @return A destination name in adressable hash form, for an app_name and a number of aspects
     */
    public static byte[] hash(Identity identity, @NonNull String appName, String... aspects) throws IOException {
        var addrHashMaterial = subarray(
                fullHash(expandName(null, appName, aspects).getBytes(UTF_8)),
                0,
                NAME_HASH_LENGTH / 8
        );
        if (nonNull(identity)) {
            addrHashMaterial = concatArrays(addrHashMaterial, identity.getHash());
        }

        return subarray(fullHash(addrHashMaterial), 0, TRUNCATED_HASHLENGTH / 8);
    }
}
