package id.czn.patch;

import id.czn.patch.ILog;

interface IPatchService {
    void destroy() = 16777114; // called by the Shizuku server when unbinding

    /** cmd: apply | restore | check | version. Returns "" (or the version for "version") on success, else "!" + error. */
    String run(String cmd, String dataDir, ILog log) = 1;
}
