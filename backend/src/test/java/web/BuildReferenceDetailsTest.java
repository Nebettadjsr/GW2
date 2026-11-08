package web;

import model.BuildResourceType;
import org.junit.jupiter.api.Test;
import web.dto.BuildReferenceDetails;

import static org.junit.jupiter.api.Assertions.*;

class BuildReferenceDetailsTest {
    @Test
    void formatsSuppliedFactsWithoutInventingDamageAndNeverReturnsUpstreamImagesOrMarkup() {
        var details = BuildReferenceDetails.of(BuildResourceType.TRAITS, 914, """
                {"description":"Your <c=@reminder>strike damage</c> is increased.", "facts":[
                  {"type":"Percent","text":"Damage Increase","percent":3,
                   "icon":"https://render.guildwars2.com/file/ABCDEF0123456789/1234.png"},
                  {"type":"Damage","text":"Damage","hit_count":2,"dmg_multiplier":1.5},
                  {"type":"Recharge","text":"Recharge","value":20},
                  {"type":"Number","text":"Targets","value":5,"icon":"https://other.example/a.png"},
                  {"type":"Buff","text":"Might","status":"Might","duration":8,"apply_count":3}
                ]}
                """);
        assertEquals("Your strike damage is increased.", details.description());
        assertEquals("3%", details.facts().get(0).value());
        assertTrue(details.facts().get(0).iconUrl().startsWith("/api/resources/traits/914/fact-0/"));
        assertEquals("2 hits", details.facts().get(1).value());
        assertEquals("20 s", details.facts().get(2).value());
        assertNull(details.facts().get(3).iconUrl());
        assertEquals("Might · 8 s · 3 stacks", details.facts().get(4).value());
        assertNull(BuildReferenceDetails.of(BuildResourceType.SKILLS, 1, null).description());
        assertNull(BuildReferenceDetails.of(BuildResourceType.SKILLS, 1, "null").facts());
    }
}
