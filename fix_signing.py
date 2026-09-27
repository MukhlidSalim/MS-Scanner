import re
with open("app/build.gradle.kts", "r", encoding="utf-8") as f:
    content = f.read()

# Remove the debugConfig creation completely
new_content = re.sub(r'create\("debugConfig"\) \{.*?\}', '', content, flags=re.DOTALL)
# And remove signingConfig = signingConfigs.getByName("debugConfig") from debug {} block
new_content = re.sub(r'signingConfig\s*=\s*signingConfigs\.getByName\("debugConfig"\)', '', new_content)

with open("app/build.gradle.kts", "w", encoding="utf-8") as f:
    f.write(new_content)
