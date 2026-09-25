// IUnifiedService.aidl
package io.github.p1neapplexpress.openflux;

interface IUnifiedService {
    boolean isVpnRunning();
    void    stopVpn();

    boolean isFServiceRunning();
    void    stopOpenFluxNative();
    void    startOpenFluxNative(String transport, in String[] args);
    void    startTun2Socks();
    int     getFd();
}
