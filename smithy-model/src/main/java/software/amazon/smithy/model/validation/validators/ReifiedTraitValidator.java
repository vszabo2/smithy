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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.StructureIndex;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.model.traits.TraitDefinition;
import software.amazon.smithy.model.validation.AbstractValidator;
import software.amazon.smithy.model.validation.ValidationEvent;

/**
 * Ensures that any trait that is marked as {@code reified} is defined with
 * the exact same value on all structures in an inheritance hierarchy.
 *
 * <p>For example, the {@code error} trait is reified, meaning that if a
 * structure is marked with the {@code error} trait, then all structures
 * that extend it must also have an {@code error} trait with the exact same
 * value. If a structure is marked with the {@code error} trait and extends
 * from another structure, then the parent must also be marked with an
 * {@code error} trait using the exact same value of the child.
 */
public final class ReifiedTraitValidator extends AbstractValidator {
    @Override
    public List<ValidationEvent> validate(Model model) {
        StructureIndex structureIndex = model.getKnowledge(StructureIndex.class);
        Set<String> reifiedTraits = model.getTraitDefinitions().stream()
                .filter(TraitDefinition::isReified)
                .map(TraitDefinition::getFullyQualifiedName)
                .collect(Collectors.toSet());

        return model.getShapeIndex().shapes(StructureShape.class)
                .flatMap(struct -> validateStruct(structureIndex, reifiedTraits, struct).stream())
                .collect(Collectors.toList());
    }

    private List<ValidationEvent> validateStruct(
            StructureIndex structureIndex, Set<String> reified, StructureShape struct) {
        StructureShape parent = structureIndex.getParent(struct).orElse(null);
        if (parent == null) {
            return Collections.emptyList();
        }

        List<ValidationEvent> events = new ArrayList<>();
        // Find any traits reified traits set on the parent and child.
        Map<String, Trait> parentTraits = getReifiedTrait(parent, reified);
        Map<String, Trait> childTraits = getReifiedTrait(struct, reified);

        for (Map.Entry<String, Trait> entry : parentTraits.entrySet()) {
            // Is the child missing the trait?
            if (!childTraits.containsKey(entry.getKey())) {
                events.add(error(struct, String.format(
                        "Structure is missing reified trait `%s` defined on parent structure, `%s`, with value `%s`",
                        entry.getKey(), parent.getId(), Node.printJson(entry.getValue().toNode()))));
            } else if (!entry.getValue().equals(childTraits.get(entry.getKey()))) {
                // Is the child trait value different?
                events.add(error(struct, String.format(
                        "Structure has a different reified trait value for `%s` than its parent structure, `%s`: "
                        + "`%s` vs `%s`",
                        entry.getKey(), parent.getId(),
                        Node.printJson(childTraits.get(entry.getKey()).toNode()),
                        Node.printJson(entry.getValue().toNode()))));
            }
        }

        for (Map.Entry<String, Trait> entry : childTraits.entrySet()) {
            // Is the parent missing the trait?
            if (!parentTraits.containsKey(entry.getKey())) {
                events.add(error(struct, String.format(
                        "Structure defines a reified trait value for `%s` that is missing from its parent, `%s`, "
                        + "with value `%s`. This trait must be applied using the exact same value across all shapes "
                        + "in the hierarchy.",
                        entry.getKey(), parent.getId(), Node.printJson(entry.getValue().toNode()))));
            }
        }

        return events;
    }

    private Map<String, Trait> getReifiedTrait(StructureShape struct, Set<String> reified) {
        return struct.getAllTraits().entrySet().stream()
                .filter(entry -> reified.contains(entry.getKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
