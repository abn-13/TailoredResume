package com.bu.tailorsg.jobs.source;

import com.bu.tailorsg.jobs.JobsProperties;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Checks a pasted link before anything is fetched: http(s) only, and (unless allowed for tests) never a
 * loopback, private or link-local address, so a link cannot make the app probe your own network.
 */
@Component
public class UrlGuard {

    private final boolean allowPrivate;

    public UrlGuard(JobsProperties props) {
        this.allowPrivate = props.allowPrivateHosts();
    }

    /** @throws IllegalArgumentException with a message for the user */
    public URI check(String text) {
        URI uri;
        try {
            uri = URI.create(text.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("That does not look like a link.");
        }
        if (uri.getScheme() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))
                || uri.getHost() == null) {
            throw new IllegalArgumentException("Use a full http(s) link to the job posting.");
        }
        if (!allowPrivate) {
            try {
                for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                    if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                            || address.isAnyLocalAddress()) {
                        throw new IllegalArgumentException("Links to local or private network addresses are not fetched.");
                    }
                }
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Could not find the site " + uri.getHost() + ".");
            }
        }
        return uri;
    }
}
