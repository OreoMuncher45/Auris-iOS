package com.music.auris.ui

import androidx.compose.ui.graphics.vector.ImageVector
import coil3.request.ImageRequest
import coil3.request.transformations
import com.music.auris.data.LocalGroups
import com.music.auris.data.canvas.AppleArtistArt
import com.music.auris.data.canvas.AppleArtistArtRepository
import com.music.auris.data.canvas.ArtistSignature
import com.music.auris.data.canvas.ArtistSignatureRepository
import com.music.auris.data.canvas.CanvasArtwork
import com.music.auris.data.canvas.CanvasRepository
import com.music.auris.data.canvas.SignatureInkTransformation
import com.music.auris.ui.screens.deviceFolderIcon
import com.music.auris.ui.screens.DetailHost
import kotlinx.coroutines.flow.StateFlow

object AndroidDetailHost : DetailHost {
    override fun cachedArt(name: String): AppleArtistArt? = AppleArtistArtRepository.cached(name)
    override suspend fun artFor(name: String): AppleArtistArt? = AppleArtistArtRepository.artFor(name)
    override val signatureUpdates: StateFlow<Int> get() = ArtistSignatureRepository.updates
    override fun signatureBase(enabled: Boolean, url: String) = ArtistSignatureRepository.effectiveBaseUrl(enabled, url)
    override fun cachedSignature(name: String, base: String?): ArtistSignature? = ArtistSignatureRepository.cached(name, base)
    override suspend fun signatureFor(name: String, base: String?): ArtistSignature? = ArtistSignatureRepository.signatureFor(name, base)
    override fun ImageRequest.Builder.signatureInk() { transformations(SignatureInkTransformation) }
    override suspend fun canvasForAlbum(album: String, artist: String): CanvasArtwork? = CanvasRepository.canvasForAlbum(album, artist)
    override fun isLocalArtist(browseId: String) = LocalGroups.isArtist(browseId)
    override fun folderIcon(browseId: String): ImageVector? = deviceFolderIcon(browseId)
}
