package dev.geomys;

import android.os.ParcelFileDescriptor;

interface IProfileTunnel {
    int getProtocolVersion();
    String getProfileInfoJson();
    String getServerConfigsJson();
    ParcelFileDescriptor openTcpStream(String serverConfigId, int clientUserId,
            String clientLabel, String connectionId);
    ParcelFileDescriptor openUdpStream(String serverConfigId, int clientUserId,
            String clientLabel, String connectionId);
    void closeStream(String connectionId);
}
