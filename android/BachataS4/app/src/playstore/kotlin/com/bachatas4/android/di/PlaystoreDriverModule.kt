package com.bachatas4.android.di

import android.content.Context
import com.bachatas4.android.feature.drivers.DriverManagerBackend
import com.bachatas4.android.feature.drivers.DriverManagerCapabilities
import com.bachatas4.android.runtime.driver.BundledTurnipInstaller
import com.bachatas4.android.runtime.driver.BundledTurnipPackage
import com.bachatas4.android.runtime.driver.BundledTurnipSpec
import com.bachatas4.android.runtime.driver.DriverPackageSource
import com.bachatas4.android.runtime.driver.DriverRegistry
import com.bachatas4.android.runtime.driver.InstalledDriver
import com.bachatas4.android.runtime.driver.TurnipPackageInstaller
import com.bachatas4.android.runtime.driver.TurnipReleaseAsset
import com.bachatas4.android.runtime.process.RuntimeVulkanDriver
import com.bachatas4.android.runtime.process.RuntimeVulkanDriverIds
import com.bachatas4.android.runtime.process.VulkanDriverConfiguration
import com.bachatas4.android.runtime.process.VulkanDriverResolveContext
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.ByteArrayInputStream
import java.nio.file.Path
import javax.inject.Singleton

/**
 * Play Store driver backend: APK-bundled Turnip packages plus opt-in experimental Vortek,
 * plus user-imported custom Turnip ZIPs.
 *
 * NOTE: ZIP import is enabled here per explicit request from the app owner, who does not
 * distribute this build via the Play Store. If this build is ever submitted to Play, letting
 * users import and load arbitrary native driver binaries at runtime is the kind of behavior
 * that can trigger a policy review (Device and Network Abuse / unauthorized code execution) --
 * re-check Play's current policies before shipping a Play-distributed build with this enabled.
 *
 * Stale Turnip ids still fall back to the first bundled package; system-vortek is never remapped.
 */
internal class PlaystoreDriverManagerBackend(context: Context) : DriverManagerBackend {
    private val assets = context.assets
    private val root = context.filesDir.toPath().resolve("vulkan-drivers/installed")
    private val packages: List<BundledTurnipPackage> = BundledTurnipSpec.ALL
    private val installers: List<BundledTurnipInstaller> = packages.map { pkg ->
        BundledTurnipInstaller(
            registryRoot = root,
            openAsset = { assets.open(pkg.assetPath) },
            packageSpec = pkg,
        )
    }
    private val registry = DriverRegistry(root)
    private val importInstaller = TurnipPackageInstaller(root)
    private val bundledSha256 = packages.map { it.sha256.lowercase() }.toSet()

    override fun capabilities() = DriverManagerCapabilities(
        remoteCatalogEnabled = false,
        importEnabled = true,
        deleteEnabled = true,
        statusMessage = PLAY_STATUS,
    )

    override fun installed(): List<InstalledDriver> {
        ensureAllInstalled()
        return registry.listInstalled()
    }

    override fun releases(force: Boolean): List<TurnipReleaseAsset> = emptyList()

    override fun download(asset: TurnipReleaseAsset, progress: (Long, Long) -> Unit): InstalledDriver {
        throw UnsupportedOperationException(REMOTE_DISABLED)
    }

    override fun importZip(bytes: ByteArray, assetName: String): InstalledDriver = importInstaller.install(
        ByteArrayInputStream(bytes),
        DriverPackageSource(assetName = assetName),
    )

    override fun remove(id: String): Boolean {
        val existing = registry.resolve(id)
            ?: return false
        require(!existing.metadata.sha256.lowercase().let { it in bundledSha256 }) {
            "Bundled Turnip drivers cannot be removed"
        }
        return registry.remove(id)
    }

    override fun configurationFor(driverId: String, context: VulkanDriverResolveContext): VulkanDriverConfiguration {
        when (driverId) {
            RuntimeVulkanDriverIds.SYSTEM ->
                return VulkanDriverConfiguration.resolve(RuntimeVulkanDriver.SYSTEM, context)
            RuntimeVulkanDriverIds.SYSTEM_VORTEK ->
                return VulkanDriverConfiguration.resolve(RuntimeVulkanDriver.SYSTEM_VORTEK, context)
        }
        val bundled = ensureAllInstalled()
        val driver = bundled.firstOrNull { it.metadata.id == driverId }
            ?: registry.resolve(driverId)
            ?: bundled.first() // default mojo-26.1 (BundledTurnipSpec.DEFAULT / ALL order)
        return VulkanDriverConfiguration.resolve(driver, context.runtimeRoot)
    }

    override fun autoSelectDriverId(): String = ensureAllInstalled().first().metadata.id

    private fun ensureAllInstalled(): List<InstalledDriver> = installers.map { it.ensureInstalled() }

    companion object {
        const val REMOTE_DISABLED =
            "Remote driver catalogue browsing is not available in this build."
        const val PLAY_STATUS =
            "Bundled Turnip lines: mojo-26.1, mojo-25.0, and gen8. " +
                "System Driver (Vortek, Experimental) is opt-in. " +
                "Custom Turnip drivers can be imported as a ZIP. " +
                "Bundled driver updates are delivered through app updates."
    }
}

@Module
@InstallIn(SingletonComponent::class)
object PlaystoreDriverModule {
    @Provides
    @Singleton
    fun backend(@ApplicationContext context: Context): DriverManagerBackend =
        PlaystoreDriverManagerBackend(context)
}
