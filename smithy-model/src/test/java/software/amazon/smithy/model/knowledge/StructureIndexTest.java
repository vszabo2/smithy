package software.amazon.smithy.model.knowledge;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.shapes.MemberShape;
import software.amazon.smithy.model.shapes.ShapeId;
import software.amazon.smithy.model.shapes.ShapeIndex;
import software.amazon.smithy.model.shapes.StringShape;
import software.amazon.smithy.model.shapes.StructureShape;
import software.amazon.smithy.model.traits.DocumentationTrait;
import software.amazon.smithy.model.traits.DynamicTrait;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.model.traits.TraitDefinition;
import software.amazon.smithy.utils.ListUtils;
import software.amazon.smithy.utils.MapUtils;

public class StructureIndexTest {
    @Test
    public void findsInheritedTraits() {
        TraitDefinition inherited = TraitDefinition.builder()
                .name("foo.baz#inherited")
                .inherited(true)
                .build();
        Trait inheritedTrait = new DynamicTrait(inherited.getFullyQualifiedName(), Node.from(true));
        StructureShape a = StructureShape.builder()
                .id("foo.baz#A")
                .addTrait(new DocumentationTrait("test1"))
                .addTrait(inheritedTrait)
                .build();
        StructureShape b = StructureShape.builder()
                .id("foo.baz#B")
                .isa(a)
                .build();
        StructureShape c = StructureShape.builder()
                .id("foo.baz#C")
                .isa(b)
                .addTrait(new DocumentationTrait("test2"))
                .build();
        Model model = Model.assembler().addShapes(a, b, c).addTraitDefinition(inherited).assemble().unwrap();
        StructureIndex structureIndex = model.getKnowledge(StructureIndex.class);

        assertThat(structureIndex.getTraits(a), equalTo(a.getAllTraits()));
        assertThat(structureIndex.getTraits(b), equalTo(MapUtils.of(inheritedTrait.getName(), inheritedTrait)));
        assertThat(structureIndex.getTraits(c), equalTo(MapUtils.of(
                inheritedTrait.getName(), inheritedTrait,
                DocumentationTrait.NAME, new DocumentationTrait("test2"))));
        assertThat(structureIndex.getTrait(a, DocumentationTrait.class),
                   equalTo(Optional.of(new DocumentationTrait("test1"))));
        assertThat(structureIndex.getTrait(b, DocumentationTrait.class),
                   equalTo(Optional.empty()));
        assertThat(structureIndex.getTrait(c, DocumentationTrait.class),
                   equalTo(Optional.of(new DocumentationTrait("test2"))));
        assertThat(structureIndex.findTrait(c, inheritedTrait.getName()),
                   equalTo(Optional.of(inheritedTrait)));
    }

    @Test
    public void loadsParentHierarchy() {
        StructureShape a = StructureShape.builder().id("foo.baz#A").build();
        StructureShape b = StructureShape.builder().id("foo.baz#B").isa(a).build();
        StructureShape c = StructureShape.builder().id("foo.baz#C").isa(b).build();
        Model model = Model.assembler().addShapes(a, b, c).assemble().unwrap();
        StructureIndex structureIndex = model.getKnowledge(StructureIndex.class);

        assertThat(structureIndex.getParent(a), equalTo(Optional.empty()));
        assertThat(structureIndex.getParent(b), equalTo(Optional.of(a)));
        assertThat(structureIndex.getParent(c), equalTo(Optional.of(b)));

        assertThat(structureIndex.getParents(a), equalTo(ListUtils.of()));
        assertThat(structureIndex.getParents(b), equalTo(ListUtils.of(a)));
        assertThat(structureIndex.getParents(c), equalTo(ListUtils.of(b, a)));

        assertThat(structureIndex.getSubtypes(a), equalTo(ListUtils.of(b)));
        assertThat(structureIndex.getSubtypes(b), equalTo(ListUtils.of(c)));
        assertThat(structureIndex.getSubtypes(c), equalTo(ListUtils.of()));
    }

    @Test
    public void gracefullyHandlesBrokenModels() {
        StringShape a = StringShape.builder().id("foo.baz#A").build();
        StructureShape b = StructureShape.builder().id("foo.baz#B").isa(a).build();
        StructureShape c = StructureShape.builder().id("foo.baz#C").isa(ShapeId.from("missing.foo#Baz")).build();
        ShapeIndex index = ShapeIndex.builder().addShapes(a, b, c).build();
        Model model = Model.builder().shapeIndex(index).build();
        StructureIndex structureIndex = model.getKnowledge(StructureIndex.class);

        assertThat(structureIndex.getParent(a), equalTo(Optional.empty()));
        assertThat(structureIndex.getParent(b), equalTo(Optional.empty()));
        assertThat(structureIndex.getParent(c), equalTo(Optional.empty()));

        assertThat(structureIndex.getParents(a), equalTo(ListUtils.of()));
        assertThat(structureIndex.getParents(b), equalTo(ListUtils.of()));
        assertThat(structureIndex.getParents(c), equalTo(ListUtils.of()));
    }

    @Test
    public void resolvesMembersWithCorrectPrecedence() {
        MemberShape memberA = MemberShape.builder().id("foo.baz#A$a").target("smithy.api#String").build();
        MemberShape memberB1 = MemberShape.builder().id("foo.baz#A$b").target("smithy.api#String").build();
        StructureShape a = StructureShape.builder()
                .id("foo.baz#A")
                .addMember(memberA)
                .addMember(memberB1)
                .build();
        MemberShape memberB2 = MemberShape.builder().id("foo.baz#B$b").target("smithy.api#String").build();
        MemberShape memberBB = MemberShape.builder().id("foo.baz#B$bb").target("smithy.api#String").build();
        StructureShape b = StructureShape.builder()
                .id("foo.baz#B")
                .isa(a)
                .addMember(memberB2)
                .addMember(memberBB)
                .build();
        MemberShape memberC = MemberShape.builder().id("foo.baz#C$c").target("smithy.api#String").build();
        StructureShape c = StructureShape.builder()
                .id("foo.baz#C")
                .isa(b)
                .addMember(memberC)
                .build();
        ShapeIndex index = ShapeIndex.builder()
                .addShapes(a, b, c, memberA, memberB1, memberB2, memberBB, memberC)
                .build();
        Model model = Model.builder().shapeIndex(index).build();
        StructureIndex structureIndex = model.getKnowledge(StructureIndex.class);

        assertThat(structureIndex.getMembers(a), equalTo(a.getAllMembers()));
        assertThat(structureIndex.getMembers(b), equalTo(MapUtils.of(
                memberA.getMemberName(), memberA,
                memberB1.getMemberName(), memberB1,
                memberBB.getMemberName(), memberBB)));
        assertThat(structureIndex.getMembers(c), equalTo(MapUtils.of(
                memberA.getMemberName(), memberA,
                memberB1.getMemberName(), memberB1,
                memberBB.getMemberName(), memberBB,
                memberC.getMemberName(), memberC)));
    }

    @Test
    public void getsMissingMembers() {
        Model model = Model.builder().build();
        StructureIndex structureIndex = model.getKnowledge(StructureIndex.class);

        assertThat(structureIndex.getMembers(ShapeId.from("foo.baz#Bar")), equalTo(MapUtils.of()));
    }

    @Test
    public void getsMissingTraits() {
        Model model = Model.builder().build();
        StructureIndex structureIndex = model.getKnowledge(StructureIndex.class);

        assertThat(structureIndex.getTraits(ShapeId.from("foo.baz#Bar")), equalTo(MapUtils.of()));
    }
}
