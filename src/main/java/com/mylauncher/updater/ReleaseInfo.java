package com.mylauncher.updater;

import java.net.URI;

public record ReleaseInfo(String version, URI archiveUri, long archiveSize,
                          String sha256, URI checksumUri) {
}
