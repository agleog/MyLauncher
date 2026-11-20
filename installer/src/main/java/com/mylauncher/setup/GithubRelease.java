package com.mylauncher.setup;

import java.net.URI;

record GithubRelease(String version, URI archiveUri, long archiveSize, String sha256,
                     URI checksumUri, String releaseNotes) {
}
