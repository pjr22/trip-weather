package com.pjr22.tripweather.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response from {@code DELETE /api/admin/users/{id}}. Reports the number of
 * routes that were cascaded by the delete, split by their pre-delete state so
 * the admin can see at a glance how much data went with the user.
 *
 * <p>Soft-deleted routes are counted here because the {@code ON DELETE CASCADE}
 * FK on {@code routes.user_id} sweeps them too — they're invisible to the
 * regular SPA but they exist in the table and they're gone after this call.
 * Email-verification and password-reset rows also cascade but they're not
 * reported separately; they have no operator-meaningful "count" the way routes
 * do.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserDeleteResult {

    private long activeRoutesDeleted;
    private long softDeletedRoutesDeleted;
}
