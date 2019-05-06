/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.model.knowledge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.ShapeIndex;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.shapes.ToShapeId;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.model.traits.TraitDefinition;
import software.amazon.smithy.utils.OptionalUtils;

/**
 * Provides a simple way to traverse parent and child structure hierarchies.
 */
public final class StructureIndex implements KnowledgeIndex {
    private final Set<String> inheritedTraits;
    private final Map<ShapeId, List<StructureShape>> parents = new HashMap<>();
    private final Map<ShapeId, StructureShape> structs = new HashMap<>();
    private final ConcurrentMap<ShapeId, Map<String, MemberShape>> members = new ConcurrentHashMap<>();
    private final ConcurrentMap<ShapeId, Map<String, Trait>> traits = new ConcurrentHashMap<>();

    public StructureIndex(Model model) {
        inheritedTraits = model.getTraitDefinitions().stream()
                .filter(TraitDefinition::isInherited)
                .map(TraitDefinition::getFullyQualifiedName)
                .collect(Collectors.toSet());

        ShapeIndex index = model.getShapeIndex();
        index.shapes(StructureShape.class).forEach(struct -> {
            structs.put(struct.getId(), struct);
            OptionalUtils.ifPresentOrElse(
                    struct.getParent(),
                    parent -> parents.put(struct.getId(), resolveParents(index, struct)),
                    () -> parents.put(struct.getId(), Collections.emptyList())
            );
        });
    }

    /**
     * Gets the parent structure of a structure.
     * @param structure Structure to get the parent of.
     * @return Returns the optionally found parent.
     */
    public Optional<StructureShape> getParent(ToShapeId structure) {
        List<StructureShape> parents = getParents(structure);
        return parents.isEmpty() ? Optional.empty() : Optional.of(parents.get(0));
    }

    /**
     * Get all of the parent structrues of a structure in order from closest
     * to furthest away.
     *
     * @param structure Structure to get the parents of.
     * @return Returns the parents of the structure (or an empty list).
     */
    public List<StructureShape> getParents(ToShapeId structure) {
        return parents.getOrDefault(structure.toShapeId(), Collections.emptyList());
    }

    /**
     * Gets the combined members of a structure and all of its parents'
     * members.
     *
     * <p>This method does not validate that subtypes do not override parent
     * members (that validation is performed elsewhere). It will, however,
     * always favor the members defined on parent types if there is a
     * conflict.
     *
     * @param structure Structure to get the members of.
     * @return Returns the resolved members of the structure.
     */
    public Map<String, MemberShape> getMembers(ToShapeId structure) {
        return members.computeIfAbsent(structure.toShapeId(), id -> {
            if (!structs.containsKey(id)) {
                return Collections.emptyMap();
            }
            Map<String, MemberShape> members = new HashMap<>(structs.get(id).getAllMembers());
            for (StructureShape parent : getParents(id)) {
                members.putAll(parent.getAllMembers());
            }
            return Collections.unmodifiableMap(members);
        });
    }

    /**
     * Gets the combined traits of a structure shape.
     *
     * <p>Only traits defined on parent shapes that are marked as
     * {@code inherited} are returned in the result. Traits defined on a
     * subtype override any traits defined on a parent.
     *
     * <p>Each key in the returned map is a fully-qualified trait name,
     * and each value is the value of the trait.
     *
     * <p>If you need to get all of the traits set on every shape in
     * the hierarchy and not just inherited traits, then use
     * {@link #getParents(ToShapeId)} and call {@link Shape#getAllTraits()}
     * on each result.
     *
     * @param structure Structure to get the traits of.
     * @return Returns the resolved traits of the structure.
     */
    public Map<String, Trait> getTraits(ToShapeId structure) {
        return traits.computeIfAbsent(structure.toShapeId(), id -> {
            if (!structs.containsKey(id)) {
                return Collections.emptyMap();
            }

            Map<String, Trait> traits = new HashMap<>();
            List<StructureShape> parents = getParents(id);
            for (int i = parents.size() - 1; i >= 0; i--) {
                for (Trait trait : parents.get(i).getAllTraits().values()) {
                    if (inheritedTraits.contains(trait.getName())) {
                        traits.put(trait.getName(), trait);
                    }
                }
            }

            traits.putAll(structs.get(id).getAllTraits());
            return Collections.unmodifiableMap(traits);
        });
    }

    /**
     * Gets a trait of a specific type from a structure or one of its parents
     * if the parent trait is defined as {@code inherited}.
     *
     * @param structure Structure to get the trait from.
     * @param trait The type of trait to get.
     * @param <T> The trait type.
     * @return Returns the optionally found trait.
     */
    @SuppressWarnings("unchecked")
    public <T extends Trait> Optional<T> getTrait(ToShapeId structure, Class<T> trait) {
        for (Trait t : getTraits(structure).values()) {
            if (trait.isInstance(t)) {
                return Optional.of((T) t);
            }
        }
        return Optional.empty();
    }

    /**
     * Gets a trait by name from the structure or one of its parents
     * if the parent trait is defined as {@code inherited}.
     *
     * <p>If a trait name is provided with no namespace, the prelude
     * namespace of {@code smithy.api} is assumed.
     *
     * @param structure Structure to get the trait from.
     * @param traitName Name of the trait to get.
     * @return Returns the optionally found trait.
     */
    public Optional<Trait> findTrait(ToShapeId structure, String traitName) {
        return Optional.ofNullable(getTraits(structure).get(Trait.makeAbsoluteName(traitName)));
    }

    /**
     * Gets all of the direct subtypes of the given structure.
     *
     * @param structure Structure to get the direct subtypes of.
     * @return Returns the structures that are a subtype.
     */
    public List<StructureShape> getSubtypes(ToShapeId structure) {
        ShapeId id = structure.toShapeId();
        return structs.values().stream()
                .filter(s -> s.getParent().filter(p -> p.equals(id)).isPresent())
                .collect(Collectors.toList());
    }

    private static List<StructureShape> resolveParents(ShapeIndex index, StructureShape shape) {
        List<StructureShape> shapes = new ArrayList<>();
        Set<ShapeId> visited = new HashSet<>();
        visited.add(shape.getId());

        while (shape.getParent().isPresent()) {
            ShapeId parent = shape.getParent().get();
            StructureShape maybeParent = index.getShape(parent)
                    .flatMap(Shape::asStructureShape)
                    .orElse(null);

            if (visited.contains(parent) || maybeParent == null) {
                return shapes;
            }

            visited.add(parent);
            shapes.add(maybeParent);
            shape = maybeParent;
        }

        return Collections.unmodifiableList(shapes);
    }
}
