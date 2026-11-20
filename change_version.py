import re
with open('app/build.gradle.kts', 'r') as f:
    content = f.read()
content = content.replace('versionCode = 54', 'versionCode = 55')
content = content.replace('versionName = "1.9.27"', 'versionName = "1.9.28"')
with open('app/build.gradle.kts', 'w') as f:
    f.write(content)
print('Done')