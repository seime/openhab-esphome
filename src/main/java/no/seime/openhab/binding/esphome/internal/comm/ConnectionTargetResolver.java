/**
 * Copyright (c) 2023 Contributors to the Seime Openhab Addons project
 * <p>
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 * <p>
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 * <p>
 * SPDX-License-Identifier: EPL-2.0
 */
package no.seime.openhab.binding.esphome.internal.comm;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.net.InetAddresses;

import no.seime.openhab.binding.esphome.internal.discovery.ESPHomeMDNSHostnameResolver;

/**
 * Resolves the configured hostname of a device to the address to connect to. Tries, in order: a literal IP address,
 * the mDNS cache, DNS, and finally the last IP address the device was reached at.
 *
 * @author Arne Seime - Initial contribution
 */
@NonNullByDefault
public class ConnectionTargetResolver {

    private final Logger logger = LoggerFactory.getLogger(ConnectionTargetResolver.class);

    private final ESPHomeMDNSHostnameResolver mdnsHostnameResolver;

    public ConnectionTargetResolver(ESPHomeMDNSHostnameResolver mdnsHostnameResolver) {
        this.mdnsHostnameResolver = mdnsHostnameResolver;
    }

    /**
     * @param lastKnownIpAddress the IP address the device was last connected at, used if the hostname cannot be
     *            resolved
     */
    public ResolvedConnectionTarget resolve(String configuredHostname, @Nullable String lastKnownIpAddress,
            String logPrefix) throws ProtocolAPIError {
        if (InetAddresses.isInetAddress(configuredHostname)) {
            InetAddress configuredAddress = InetAddresses.forString(configuredHostname);
            String configuredIpAddress = configuredAddress.getHostAddress();
            return new ResolvedConnectionTarget(configuredIpAddress, configuredIpAddress, configuredHostname,
                    configuredHostname, false);
        }

        Optional<String> mdnsIp = mdnsHostnameResolver.resolve(configuredHostname);
        if (mdnsIp.isPresent()) {
            String ip = mdnsIp.get();
            logger.debug("[{}] Resolved '{}' via mDNS cache to {}", logPrefix, configuredHostname, ip);
            return new ResolvedConnectionTarget(ip, ip, configuredHostname + " (mDNS " + ip + ")", configuredHostname,
                    true);
        }

        try {
            InetAddress resolvedAddress = InetAddress.getByName(configuredHostname);
            return new ResolvedConnectionTarget(resolvedAddress.getHostAddress(), resolvedAddress.getHostAddress(),
                    configuredHostname, configuredHostname, true);
        } catch (UnknownHostException e) {
            String cachedIpAddress = StringUtils.trimToNull(lastKnownIpAddress);
            if (cachedIpAddress != null && InetAddresses.isInetAddress(cachedIpAddress)) {
                logger.warn("[{}] Failed to resolve '{}'. Falling back to cached IP {}", logPrefix, configuredHostname,
                        cachedIpAddress);
                return new ResolvedConnectionTarget(cachedIpAddress, cachedIpAddress,
                        configuredHostname + " (cached " + cachedIpAddress + ")", cachedIpAddress, true);
            }
            throw new ProtocolAPIError("Failed to resolve hostname '" + configuredHostname + "'", e);
        }
    }

    /**
     * @param connectHost the host to open the connection to
     * @param ipAddress the IP address the device is reached at
     * @param logTarget describes the target in log messages
     * @param statusTarget describes the target in the Thing status
     * @param cacheLastKnownIpAddress whether the IP address should be remembered as fallback for later connections;
     *            false when a literal IP address is configured
     */
    public record ResolvedConnectionTarget(String connectHost, String ipAddress, String logTarget, String statusTarget,
            boolean cacheLastKnownIpAddress) {
    }
}
