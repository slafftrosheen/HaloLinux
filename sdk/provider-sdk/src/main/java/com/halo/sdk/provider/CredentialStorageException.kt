package com.halo.sdk.provider

/** Content-free boundary failure; credentials and cryptographic exception messages stay private. */
class CredentialStorageException(val operation: String) : IllegalStateException("Protected credential storage unavailable ($operation)")
