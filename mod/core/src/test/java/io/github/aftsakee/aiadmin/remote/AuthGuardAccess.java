package io.github.aftsakee.aiadmin.remote;

import java.net.InetAddress;

/** 测试用：访问 AuthGuard 的包内方法。 */
public final class AuthGuardAccess {
    private AuthGuardAccess() {}

    public static String realClient(InetAddress peer, String xff) {
        return AuthGuard.realClient(peer, xff);
    }
}
