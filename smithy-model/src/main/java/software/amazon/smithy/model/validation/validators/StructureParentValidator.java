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

package software.amazon.smithy.model.validation.validators;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.StructureIndex;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.ShapeIndex;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.FinalTrait;
import software.amazon.smithy.model.validation.AbstractValidator;
import software.amazon.smithy.model.validation.ValidationEvent;
import software.amazon.smithy.utils.OptionalUtils;

/**
 * This validator checks for the correctness of structural inheritance.
 *
 * <ul>
 *     <li>The parent referenced in "isa" must be found.</li>
 *     <li>The parent referenced in "isa" must be a structure.</li>
 *     <li>The parent referenced in "isa" must not be marked as {@code final}.</li>
 *     <li>The parent referenced in "isa" must not cause a circular inheritance hierarchy.</li>
 *     <li>Structures must not case-insensitively redefine members defined in any parent structures.</li>
 * </ul>
 */
public final class StructureParentValidator extends AbstractValidator {
    @Override
    public List<ValidationEvent> validate(Model model) {
        ShapeIndex index = model.getShapeIndex();

        // Validate parent hierarchies are resolvable.
        List<ValidationEvent> events = index.shapes(StructureShape.class)
                .flatMap(struct -> OptionalUtils.stream(validateStruct(index, struct)))
                .collect(Collectors.toList());

        // Check for member conflicts.
        StructureIndex structureIndex = model.getKnowledge(StructureIndex.class);
        index.shapes(StructureShape.class).forEach(struct -> {
            validateMemberConflicts(struct, structureIndex).ifPresent(events::add);
        });

        return events;
    }

    private Optional<ValidationEvent> validateStruct(ShapeIndex index, StructureShape shape) {
        Set<ShapeId> visited = new HashSet<>();
        visited.add(shape.getId());

        while (shape.getParent().isPresent()) {
            ShapeId parent = shape.getParent().get();

            if (visited.contains(parent)) {
                return Optional.of(error(shape, "Structure shape has a circular inheritance hierarchy: "
                        + visited.stream().map(ShapeId::toString).collect(Collectors.joining(" < "))
                        + " < " + parent));
            }

            visited.add(parent);
            Shape maybeParent = index.getShape(parent).orElse(null);
            if (maybeParent == null) {
                // This is validated by TargetValidator, so do not emit a duplicate event here.
                return Optional.empty();
            }

            if (!(maybeParent instanceof StructureShape)) {
                return Optional.of(error(shape, String.format(
                        "Structure shape parent `%s` is a `%s` and not a structure", parent, maybeParent.getType())));
            }

            if (maybeParent.hasTrait(FinalTrait.class)) {
                return Optional.of(error(shape, String.format(
                        "Structure shape attempts to extend from `%s` which is marked with the final trait",
                        parent)));
            }

            shape = (StructureShape) maybeParent;
        }

        return Optional.empty();
    }

    private Optional<ValidationEvent> validateMemberConflicts(StructureShape current, StructureIndex index) {
        List<MemberShape> members = index.getParents(current).stream()
                .flatMap(structure -> structure.getAllMembers().values().stream())
                .collect(Collectors.toList());
        Map<String, Set<String>> conflicts = new TreeMap<>();

        for (MemberShape member : current.getAllMembers().values()) {
            for (MemberShape test : members) {
                if (test.getMemberName().equalsIgnoreCase(member.getMemberName())) {
                    conflicts.computeIfAbsent(member.getMemberName(), name -> new TreeSet<>())
                            .add(test.getId().toString());
                }
            }
        }

        if (conflicts.isEmpty()) {
            return Optional.empty();
        }

        StringBuilder message = new StringBuilder();
        message.append("Member name conflicts were found in the inheritance hierarchy of this structure: ");
        conflicts.forEach((memberName, conflictIds) -> {
            message.append("`").append(memberName).append("` conflicts with `");
            message.append(String.join("`, `", conflictIds)).append("`; ");
        });
        message.append("Member names must be case-insensitively unique across all inherited shapes.");

        return Optional.of(error(current, message.toString()));
    }
}
