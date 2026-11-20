package com.mylauncher.setup;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GithubReleaseClientTest {
    @Test
    void validatesOwnerAndRepository() {
        assertEquals("example/MyLauncher", GithubReleaseClient.validateRepository(" example/MyLauncher "));
    }

    @Test
    void rejectsUrlsAndMalformedRepositoryNames() {
        assertThrows(IllegalArgumentException.class,
                () -> GithubReleaseClient.validateRepository("https://github.com/example/MyLauncher"));
        assertThrows(IllegalArgumentException.class,
                () -> GithubReleaseClient.validateRepository("example/../other"));
    }

    @Test
    void parsesSha256DigestFromGitHub() throws IOException {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                GithubReleaseClient.parseDigest(
                        "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
    }

    @Test
    void rejectsUnsupportedOrMalformedDigests() {
        assertThrows(IOException.class, () -> GithubReleaseClient.parseDigest("sha1:abc"));
        assertThrows(IOException.class, () -> GithubReleaseClient.parseDigest("sha256:bad"));
    }
}
