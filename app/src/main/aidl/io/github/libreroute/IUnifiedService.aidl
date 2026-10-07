// IUnifiedService.aidl
package io.github.libreroute;

interface IUnifiedService {
    boolean isVpnRunning();
    void    stopVpn();

    boolean isFServiceRunning();
    void    stopLibreRouteNative();
    void    startLibreRouteNative(String transport, in String[] args);
    void    startTun2Socks();
    int     getFd();
}
