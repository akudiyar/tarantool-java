package org.tarantool.cluster;

import org.tarantool.TarantoolClientConfig;
import org.tarantool.TarantoolClientImpl;
import org.tarantool.TarantoolClientOps;
import org.tarantool.TarantoolClusterClientConfig;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A cluster nodes discoverer based on calling a predefined function
 * which returns list of nodes.
 *
 * The function has to have no arguments and return list of
 * the strings which follow <code>host[:port]</code> format
 */
public class TarantoolClusterStoredFunctionDiscoverer implements TarantoolClusterDiscoverer {

    private String entryInstance;
    private String entryFunction;
    private TarantoolClientConfig config;

    public TarantoolClusterStoredFunctionDiscoverer(TarantoolClusterClientConfig clientConfig) {
        this.entryInstance = clientConfig.clusterDiscoveryEntryInstance;
        this.entryFunction = clientConfig.clusterDiscoveryEntryFunction;
        this.config = clientConfig;
    }

    @Override
    public Set<String> getInstances() {
        TarantoolClientOps<Integer, List<?>, Object, List<?>> syncOperations =
                new TarantoolClientImpl(entryInstance, config).syncOps();

        List<?> list = syncOperations.call(entryFunction);
        // discoverer expects a single array result from the function now;
        // in order to protect this contract the discoverer does a strict
        // validation against the data returned;
        // this strict-mode allows us to extend the contract in a non-breaking
        // way for old clients just reserve an extra return value in
        // terms of LUA multi-result support.
        checkResult(list);

        List<Object> funcResult = (List<Object>) list.get(0);
        return funcResult.stream()
                .map(Object::toString)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Check whether the result follows the contract or not.
     * The contract is <b>single array of strings</b>
     *
     * @param result result to be validated
     */
    private void checkResult(List<?> result) {
        if (result.size() != 1) {
            throw new IllegalDiscoveryFunctionResult("Too many result values");
        }
        if (!((List<Object>)result.get(0)).stream().allMatch(item -> item instanceof String)) {
            throw new IllegalDiscoveryFunctionResult("The first value must be an array of strings");
        }
    }

}
