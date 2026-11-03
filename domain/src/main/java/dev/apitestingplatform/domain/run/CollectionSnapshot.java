package dev.apitestingplatform.domain.run;

import dev.apitestingplatform.domain.storage.SavedRequest;
import dev.apitestingplatform.domain.variables.Variable;
import java.util.*;

/** Saved request order and selected environment, captured together at run start. */
public record CollectionSnapshot(UUID collectionId, UUID workspaceId, String name, UUID environmentId,
        String environmentName, List<Variable> workspaceVariables, List<Variable> environmentVariables, List<SavedRequest> requests) {
    public CollectionSnapshot {
        Objects.requireNonNull(collectionId); Objects.requireNonNull(workspaceId); Objects.requireNonNull(name); Objects.requireNonNull(environmentName);
        workspaceVariables = List.copyOf(workspaceVariables); environmentVariables = List.copyOf(environmentVariables); requests = List.copyOf(requests);
        Variable.validate(workspaceVariables); Variable.validate(environmentVariables);
        if (requests.size() > 1000) throw new IllegalArgumentException("At most 1,000 saved requests can be included in a run.");
        if (requests.stream().map(request -> request.item().id()).distinct().count() != requests.size()) throw new IllegalArgumentException("Duplicate request in collection snapshot.");
    }
    @Override public String toString() { return "CollectionSnapshot[" + collectionId + ", " + requests.size() + " requests; values hidden]"; }
}
