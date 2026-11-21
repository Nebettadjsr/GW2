package craft;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Desired-output backend oracles with independent literals; the Ancient Bone recipe is the bundled catalog fact. */
class ForgeDesiredOutputOracleTest {
    private static final int BONE = 24358;

    // bundled catalog: Ancient Bone#recipe1 outputs 5..12 (mean 6.91), seed Bone x1
    private static Recipe bone() {
        return Recipe.forgeWithExpectedYield(-2435801, BONE, ExpectedYield.of("6.91", 5, 12), List.of(
                new Ingredient(24341, 50), new Ingredient(BONE, 1), new Ingredient(24277, 5), new Ingredient(20796, 5)));
    }

    private static ForgeProjectPlanner.Project plan(Recipe bone, long batches) {
        return LegacyForge.planner(List.of(bone)).plan(bone, batches, true, ForgeProjectPlanner.Inventory.shared(Map.of()),
                ForgeProjectPlanner.Context.NONE, Map.of(), Map.of());
    }

    @Test
    void ancientBone250NeedsFortyThreeBatchesWithExpectedAndGuaranteedNet() {
        Recipe bone = bone();
        assertEquals(43, bone.expectedYield.estimatedBatchesForOutput(250, bone.selfSeedUnits()));
        var p = plan(bone, 43);
        assertEquals(0, new BigDecimal("255.13").compareTo(p.seed().netExpectedOutput()));
        assertEquals(173, p.seed().netGuaranteedOutput());
    }

    @Test
    void ancientBone249NeedsFortyTwoBatches() {
        Recipe bone = bone();
        assertEquals(42, bone.expectedYield.estimatedBatchesForOutput(249, bone.selfSeedUnits()));
        assertEquals(0, new BigDecimal("249.22").compareTo(plan(bone, 42).seed().netExpectedOutput()));
    }

    @Test
    void cloverEighteenIsFiftyNineSingleOrSixTenAttemptsWithNoGuaranteedOutput() {
        var refs = ForgeReferenceRecipes.all();
        var single = refs.stream().filter(r -> r.possibleOutputs() == 1).findFirst().orElseThrow();
        var ten = refs.stream().filter(r -> r.possibleOutputs() == 10).findFirst().orElseThrow();
        assertEquals(59, single.estimatedBatchesForOutput(18));
        assertEquals(6, ten.estimatedBatchesForOutput(18));
        for (var ref : refs) {
            var target = ref.asPlanningTarget();
            assertTrue(LegacyForge.planner(List.of()).plan(target, 1, true, ForgeProjectPlanner.Inventory.shared(Map.of()),
                    ForgeProjectPlanner.Context.NONE, Map.of(), Map.of()).seed() == null,
                    "a Clover reference never claims a guaranteed seed/output");
        }
    }
}
