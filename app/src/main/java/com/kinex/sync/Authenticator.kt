package com.kinex.sync

/**
 * Turns the device's keypair into a bearer token, doing the two-step handshake when it has to.
 *
 * **Posting a public key is not authentication**, which is why this is two round trips instead
 * of one. A public key is public — it crosses the wire and the server stores it — so a server
 * that issued tokens to whoever presented one would hand every account to anyone who had seen
 * its key. The signature over the server's nonce is the entire security of the scheme.
 *
 * Nothing here needs the user. Tokens last 24 hours and re-authenticating is a background
 * round trip, which is what makes a short lifetime affordable.
 */
internal class Authenticator(
    private val api: KineXApi,
    private val identity: DeviceIdentity,
) {

    /** A usable token, from cache if there is one. */
    suspend fun bearerToken(): String = identity.cachedToken() ?: handshake()

    /**
     * A token the server has not already rejected.
     *
     * Called after a 401 on a token that had not expired by our clock — which happens when the
     * device's clock is off, or when the server's signing secret has been rotated. Both are
     * fixed by discarding what we have and asking again; neither is distinguishable from here,
     * and neither needs to be.
     */
    suspend fun refreshedToken(): String {
        identity.clearToken()
        return handshake()
    }

    private suspend fun handshake(): String {
        val keys = identity.keys()
        // Single-use, 60-second lifetime, and redeemed by an atomic DELETE ... RETURNING on the
        // server — so this nonce cannot be replayed and cannot be spent by a different key.
        val challenge = api.challenge(keys.publicKey)
        val response = api.token(
            publicKey = keys.publicKey,
            nonce = challenge.nonce,
            signature = keys.signChallenge(challenge.nonce),
        )
        identity.storeToken(response.accessToken, response.expiresIn, response.deviceId)
        return response.accessToken
    }
}
