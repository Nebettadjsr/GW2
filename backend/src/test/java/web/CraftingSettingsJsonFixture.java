package web;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import craft.CraftingSettings;
import web.dto.CraftingProfitRequest;

/** Valid wire fixtures use the existing boundary mapper without coupling to constructor order. */
public final class CraftingSettingsJsonFixture {
    private static final ObjectMapper JSON = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private CraftingSettingsJsonFixture() {}

    public static CraftingSettings fromSettingsJson(String settings) throws Exception {
        CraftingProfitRequest request = JSON.readValue("{\"settings\":" + settings + "}", CraftingProfitRequest.class);
        return CraftingProfitApiMapper.toEffective(request).toCraftingSettings();
    }
}
