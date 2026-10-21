# Owned archive fixtures

Both archives contain only `folder/keep.txt` (`owned archive text` plus LF) and `skip.txt` (`skip` plus LF), created for Fileway tests. No third-party content.

`owned.7z` uses LZMA2 generated with 7-Zip (`7z a -t7z -mx=1`). `owned.rar` uses RAR4 stored entries with header and payload CRC32, Unix regular-file attributes, and an explicit end header. These fixtures exercise listing, selected extraction and temporary-entry preview; they do not establish encrypted, split-volume or every codec support.
