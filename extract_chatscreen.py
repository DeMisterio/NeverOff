import json
import os

with open("ChatScreen_recovered_from_other_convo.kt", "r") as f:
    lines = f.readlines()

for i, line in enumerate(lines):
    try:
        # Check if it's a JSON line
        if line.startswith('{"step_index"'):
            data = json.loads(line)
            if data.get("type") == "TOOL_RESPONSE" or data.get("type") == "PLANNER_RESPONSE":
                content = str(data)
                if "package com.neveroff.ui.screens" in content and "fun ChatScreen" in content:
                    print(f"Found something interesting in step {data.get('step_index')}")
    except Exception as e:
        pass

# Wait, the file the user uploaded might not be pure JSONL if they copy-pasted it.
# Let's just find the first occurrence of "package com.neveroff.ui.screens" and extract from there until the end of the kotlin file.
