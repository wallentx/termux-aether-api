package com.termux.api.apis;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Probe evidence only. Passing preflight is never a claim that a guest booted. */
final class VirtualizationState {
    private VirtualizationState() {}

    static JSONObject readiness(boolean feature, boolean manage, boolean custom,
                                Integer capabilities, boolean customImageApi) throws JSONException {
        JSONArray blockers = new JSONArray();
        if (!feature) blockers.put("virtualization_feature_not_advertised");
        if (!manage) blockers.put("manage_virtual_machine_permission_denied");
        if (!custom) blockers.put("use_custom_virtual_machine_permission_denied");
        if (capabilities == null) blockers.put("manager_capabilities_unknown");
        else if ((capabilities & 2) == 0) blockers.put("non_protected_vm_not_advertised");
        if (!customImageApi) blockers.put("custom_image_api_unavailable_or_restricted");
        String status = !feature ? "unsupported" : !manage || !custom ? "denied"
                : capabilities == null || !customImageApi ? "unavailable"
                : (capabilities & 2) == 0 ? "unsupported" : "ok";
        return new JSONObject().put("status", status).put("blockers", blockers)
                .put("can_attempt_custom_vm", blockers.length() == 0)
                .put("guest_boot", "not_tested").put("arch_compatibility", "not_tested")
                .put("backend_running", JSONObject.NULL);
    }
}
