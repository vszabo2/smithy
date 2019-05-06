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

import static java.util.stream.Collectors.toList;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.knowledge.StructureIndex;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.model.traits.TraitDefinition;
import software.amazon.smithy.model.validation.AbstractValidator;
import software.amazon.smithy.model.validation.ValidationEvent;
import software.amazon.smithy.model.validation.ValidationUtils;
import software.amazon.smithy.utils.OptionalUtils;

/**
 * Validates traits that can only be applied to a single structure member.
 */
public class ExclusiveStructureMemberTraitValidator extends AbstractValidator {

    @Override
    public List<ValidationEvent> validate(Model model) {
        StructureIndex structureIndex = model.getKnowledge(StructureIndex.class);
        return model.getShapeIndex().shapes(StructureShape.class)
                .flatMap(shape -> validateExclusiveTraits(model, structureIndex, shape).stream())
                .collect(toList());
    }

    private List<ValidationEvent> validateExclusiveTraits(
            Model model, StructureIndex structureIndex, StructureShape shape) {

        // Find all members marked as structurally exclusive, and foreach,
        // ensure that multiple members are not marked with the trait.
        return structureIndex.getMembers(shape).values().stream()
                .flatMap(member -> member.getAllTraits().values().stream())
                .distinct()
                .filter(trait -> isExclusive(model, trait))
                .flatMap(t -> OptionalUtils.stream(validateExclusiveTrait(structureIndex, shape, t.getName())))
                .collect(Collectors.toList());
    }

    private boolean isExclusive(Model model, Trait trait) {
        return model.getTraitDefinition(trait.getName()).map(TraitDefinition::isStructurallyExclusive).orElse(false);
    }

    private Optional<ValidationEvent> validateExclusiveTrait(
            StructureIndex structureIndex, StructureShape shape, String traitName) {
        ShapeId structId = shape.getId();
        List<ShapeId> matches = structureIndex.getMembers(structId).values().stream()
                .filter(member -> member.findTrait(traitName).isPresent())
                .map(MemberShape::getId)
                .collect(Collectors.toList());

        if (matches.size() > 1) {
            // Only show the full shape ID if it is a member of a parent shape.
            List<String> memberNames = new ArrayList<>(matches.size());
            for (ShapeId id : matches) {
                if (structId.getNamespace().equals(id.getNamespace()) && structId.getName().equals(id.getName())) {
                    memberNames.add(id.getMember().get());
                } else {
                    memberNames.add(id.toString());
                }
            }

            return Optional.of(error(shape, String.format(
                    "The `%s` trait can be applied to only a single member of a structure, but was found on "
                    + "the following members: %s",
                    Trait.getIdiomaticTraitName(traitName),
                    ValidationUtils.tickedList(memberNames))));
        }

        return Optional.empty();
    }
}
