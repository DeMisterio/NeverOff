import json

with open("app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "r") as f:
    content = f.read()

# The user pasted a transcript JSONL directly into ChatScreen.kt!
# We need to extract ONLY the code blocks that were inside the TOOL_RESPONSE for replace_file_content or the original file.

# Let's find the first valid package declaration and read until we hit the first JSON error
import re

package_match = re.search(r"package com\.neveroff\.ui\.screens", content)
if package_match:
    start_idx = package_match.start()
    # Find the end by looking for the first `{"step_index":` which is a transcript artifact
    end_match = re.search(r'\{"step_index"', content[start_idx:])
    if end_match:
        pure_code = content[start_idx : start_idx + end_match.start()]
        with open("app/src/main/java/com/neveroff/ui/screens/ChatScreen.kt", "w") as f:
            f.write(pure_code)
        print("Fixed by truncating JSON transcript at end of file!")
    else:
        print("Could not find JSON boundary")
else:
    print("Could not find package")
