package com.veyarc.app

import com.veyarc.data.storage.AssistantProviderCatalog
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AssistantProviderLogoTest {
    @Test fun everyListedProviderHasALogoAndUnknownProvidersUseTheCustomMark() {
        AssistantProviderCatalog.providers.forEach { assertNotNull(it.id, providerLogoResource(it.id)) }
        assertNull(providerLogoResource(null))
        assertNull(providerLogoResource("unlisted-provider"))
    }
}
