The following manual changes have been made to allow for java stub generation.
Files have been copied from https://github.com/esphome/aioesphomeapi/tree/main/aioesphomeapi

1. Add `package io.esphome.api;` to both files
2. Add `option java_multiple_files = true;` to both files
3. Rename `message void {}` to `message VoidResponse {}`
4. Rename `(void)` to `(VoidResponse)` in all rpc methods