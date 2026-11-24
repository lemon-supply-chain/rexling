package dev.lemon.rex

import androidx.annotation.StringRes
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Resolve UI copy from the app's own resources so assertions follow the device locale. Literal
 * English assertions fail on a zh device as soon as the node renders a resource.
 */
internal fun appString(@StringRes id: Int, vararg args: Any): String =
    InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)
