Following manual changes have been made to allow for java stub generation

1. Add `package io.esphome.api;` to both files
2. Add `option java_multiple_files = true;` to both files
3. Rename `message void{}` to `message VoidResponse {}`
