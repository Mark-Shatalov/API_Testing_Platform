package dev.apitestingplatform.domain.storage;

import dev.apitestingplatform.domain.variables.Variable;
import java.util.*;

/** Portable values and references, independent of SQLite versions, encryption envelopes and timestamps. */
public record WorkspaceTransfer(UUID workspaceId, List<Node> nodes, List<Variable> variables,
        List<TransferEnvironment> environments, UUID selectedEnvironmentId) {
    public record Node(UUID id, LibraryItem.Kind kind, UUID parentId, String name, int position, RequestDraft request) {
        public Node {
            Objects.requireNonNull(id); Objects.requireNonNull(kind); name = Objects.requireNonNull(name).strip();
            if (name.isBlank() || name.length() > 200 || name.chars().anyMatch(c -> c < 32) || position < 0) throw new IllegalArgumentException("Invalid portable item metadata.");
            if ((kind == LibraryItem.Kind.REQUEST) != (request != null)) throw new IllegalArgumentException("Request payload belongs only to a request item.");
        }
    }
    public record TransferEnvironment(UUID id, UUID workspaceId, String name, List<Variable> variables) {
        public TransferEnvironment {
            Objects.requireNonNull(id); Objects.requireNonNull(workspaceId); name = Objects.requireNonNull(name).trim();
            variables = List.copyOf(variables); Variable.validate(variables);
            if (name.isBlank() || name.length() > 200 || name.chars().anyMatch(c -> c < 32)) throw new IllegalArgumentException("Invalid environment name.");
        }
    }
    public WorkspaceTransfer {
        Objects.requireNonNull(workspaceId); nodes = List.copyOf(nodes); variables = List.copyOf(variables); environments = List.copyOf(environments); Variable.validate(variables);
        if (nodes.isEmpty() || nodes.size() > 10000 || environments.size() > 100) throw new IllegalArgumentException("Portable workspace exceeds item/environment limits.");
        Map<UUID, Node> byId = new HashMap<>(); Set<UUID> ids = new HashSet<>();
        for (var node : nodes) { if (!ids.add(node.id())) throw new IllegalArgumentException("Duplicate item ID."); byId.put(node.id(), node); }
        var root = byId.get(workspaceId);
        if (root == null || root.kind() != LibraryItem.Kind.WORKSPACE || root.parentId() != null || root.position() != 0) throw new IllegalArgumentException("Workspace root is missing or invalid.");
        Map<UUID, Set<Integer>> positions = new HashMap<>();
        for (var node : nodes) {
            if (node.id().equals(workspaceId)) continue;
            Node parent = byId.get(node.parentId());
            boolean valid = parent != null && switch (node.kind()) {
                case WORKSPACE -> false;
                case COLLECTION -> parent.id().equals(workspaceId);
                case FOLDER -> parent.kind() == LibraryItem.Kind.COLLECTION;
                case REQUEST -> parent.kind() == LibraryItem.Kind.COLLECTION || parent.kind() == LibraryItem.Kind.FOLDER;
            };
            if (!valid) throw new IllegalArgumentException("Missing/invalid parent reference; nested folders are not supported.");
            if (!positions.computeIfAbsent(node.parentId(), key -> new HashSet<>()).add(node.position())) throw new IllegalArgumentException("Duplicate sibling position.");
            if (node.request() != null) {
                var draft = node.request();
                if (draft.assertions().stream().map(rule -> rule.id()).distinct().count() != draft.assertions().size()
                        || draft.extractions().stream().map(rule -> rule.id()).distinct().count() != draft.extractions().size()) throw new IllegalArgumentException("Duplicate rule IDs in a request.");
            }
        }
        for (var ordered : positions.values()) if (!ordered.containsAll(java.util.stream.IntStream.range(0, ordered.size()).boxed().toList())) throw new IllegalArgumentException("Sibling order must be contiguous.");
        Set<String> names = new HashSet<>();
        for (var environment : environments) {
            if (!ids.add(environment.id()) || !workspaceId.equals(environment.workspaceId())) throw new IllegalArgumentException("Duplicate environment ID or invalid workspace reference.");
            String folded = environment.name().chars().collect(StringBuilder::new, (out, c) -> out.append((char) (c >= 'A' && c <= 'Z' ? c + 32 : c)), StringBuilder::append).toString();
            if (!names.add(folded)) throw new IllegalArgumentException("Duplicate environment name.");
        }
        if (selectedEnvironmentId != null && environments.stream().noneMatch(value -> value.id().equals(selectedEnvironmentId))) throw new IllegalArgumentException("Selected environment reference is missing.");
    }
    @Override public String toString() { return "WorkspaceTransfer[" + nodes.size() + " items; values hidden]"; }
}
