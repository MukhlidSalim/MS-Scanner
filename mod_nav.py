import re

with open('app/src/main/java/com/example/ui/navigation/NavRoutes.kt', 'r', encoding='utf-8') as f:
    content = f.read()

content = re.sub(r'\s*object SignatureStudio : Screen\("signature_studio"\)', '', content)
content = re.sub(r'\s*object IdCardMerger : Screen\("id_card_merger"\)', '', content)

with open('app/src/main/java/com/example/ui/navigation/NavRoutes.kt', 'w', encoding='utf-8') as f:
    f.write(content)
