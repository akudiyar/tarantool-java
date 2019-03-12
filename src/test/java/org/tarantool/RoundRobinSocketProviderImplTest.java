package org.tarantool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("A RR socket provider")
public class RoundRobinSocketProviderImplTest extends AbstractSocketProviderTest {

    @Test
    @DisplayName("initialized with a right addresses count")
    public void testAddressesCount() {
        RoundRobinSocketProviderImpl socketProvider
                = new RoundRobinSocketProviderImpl("localhost:3301", "127.0.0.1:3302", "10.0.0.10:3303");
        assertEquals(3, socketProvider.getAddressCount());

        socketProvider.refreshAddresses(Collections.singletonList("10.0.0.1"));
        assertEquals(1, socketProvider.getAddressCount());
    }

    @Test
    @DisplayName("initialized with a right addresses values")
    public void testAddresses() {
        String[] addresses = {"localhost:3301", "127.0.0.2:3302", "10.0.0.10:3303"};
        RoundRobinSocketProviderImpl socketProvider
                = new RoundRobinSocketProviderImpl(addresses);
        assertIterableEquals(Arrays.asList(addresses), asRawHostAndPort(socketProvider.getAddresses()));

        List<String> strings = Collections.singletonList("10.0.0.1:3310");
        socketProvider.refreshAddresses(strings);
        assertIterableEquals(strings, asRawHostAndPort(socketProvider.getAddresses()));
    }

    @Test
    @DisplayName("initialized with an empty addresses list")
    public void testEmptyAddresses() {
        RoundRobinSocketProviderImpl socketProvider
                = new RoundRobinSocketProviderImpl();

        assertEquals(0, socketProvider.getAddressCount());
        assertIterableEquals(Collections.emptyList(), socketProvider.getAddresses());
    }

    @Test
    @DisplayName("produced nothing when is empty")
    public void testResultWithEmptyAddresses() throws IOException {
        RoundRobinSocketProviderImpl socketProvider
                = wrapWithMockChannelProvider(new RoundRobinSocketProviderImpl());

        assertNull(socketProvider.getAddress(0, null));

        // by timeout
        assertThrows(CommunicationException.class, () -> socketProvider.get(0, null));

        String expectedAddress = "10.0.0.1:3310";
        List<String> strings = Collections.singletonList(expectedAddress);
        socketProvider.refreshAddresses(strings);

        assertEquals(1, socketProvider.getAddressCount());
        assertIterableEquals(strings, asRawHostAndPort(socketProvider.getAddresses()));
        assertEquals(expectedAddress, extractRawHostAndPortString(socketProvider.getAddress(0, null)));

        socketProvider.get(0, null);
        assertEquals(expectedAddress, extractRawHostAndPortString(socketProvider.getLastObtainedAddress()));
    }

    @Test
    @DisplayName("initialized with a default timeout")
    public void testDefaultTimeout() {
        RoundRobinSocketProviderImpl socketProvider
                = new RoundRobinSocketProviderImpl("localhost");
        assertEquals(RoundRobinSocketProviderImpl.NO_TIMEOUT, socketProvider.getTimeout());
    }

    @Test
    @DisplayName("changed its timeout to new value")
    public void testChangingTimeout() {
        RoundRobinSocketProviderImpl socketProvider
                = new RoundRobinSocketProviderImpl("localhost");
        int expectedTimeout = 10_000;
        socketProvider.setTimeout(expectedTimeout);
        assertEquals(expectedTimeout, socketProvider.getTimeout());
    }

    @Test
    @DisplayName("changed to negative timeout with a failure")
    public void testWrongChangingTimeout() {
        RoundRobinSocketProviderImpl socketProvider
                = new RoundRobinSocketProviderImpl("localhost");
        int negativeValue = -200;
        assertThrows(IllegalArgumentException.class, () -> socketProvider.setTimeout(negativeValue));
    }

    @Test
    @DisplayName("produced socket channels using a ring pool")
    public void testAddressRingPool() throws IOException {
        String[] addresses = {"localhost:3301", "10.0.0.1:3302", "10.0.0.2:3309"};
        RoundRobinSocketProviderImpl socketProvider
                = wrapWithMockChannelProvider(new RoundRobinSocketProviderImpl(addresses));

        for (int i = 0; i < 27; i++) {
            socketProvider.get(0, null);
            assertEquals(addresses[i % 3], extractRawHostAndPortString(socketProvider.getLastObtainedAddress()));
        }
    }

    @Test
    @DisplayName("produced socket channels for the same instance")
    public void testOneAddressPool() throws IOException {
        String expectedAddress = "10.0.0.1:3301";
        String[] addresses = {expectedAddress};
        RoundRobinSocketProviderImpl socketProvider
                = wrapWithMockChannelProvider(new RoundRobinSocketProviderImpl(addresses));

        for (int i = 0; i < 5; i++) {
            socketProvider.get(0, null);
            assertEquals(expectedAddress, extractRawHostAndPortString(socketProvider.getLastObtainedAddress()));
        }
    }

    @Test
    @DisplayName("produced socket channel with an exception when an attempt number is over")
    public void testTooManyAttempts() throws IOException {
        String expectedAddress = "10.0.0.1:3301";
        String[] addresses = {expectedAddress};
        RoundRobinSocketProviderImpl socketProvider
                = wrapWithMockChannelProvider(new RoundRobinSocketProviderImpl(addresses));

        int retriesLimit = 5;
        socketProvider.setRetriesLimit(retriesLimit);

        for (int i = 0; i < retriesLimit; i++) {
            socketProvider.get(0, null);
            assertEquals(expectedAddress, extractRawHostAndPortString(socketProvider.getLastObtainedAddress()));
        }

        assertThrows(CommunicationException.class, () -> socketProvider.get(retriesLimit, null));
    }

}
