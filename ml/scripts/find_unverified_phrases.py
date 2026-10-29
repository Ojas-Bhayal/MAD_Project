import os

phrases = [
    'scientifically confirmed',
    'pathologist approved',
    'pathologist-approved',
    'confirmed disease labels',
    'confirmed labels',
    'scientifically valid'
]

matches = []
for root, dirs, files in os.walk('.'):
    if any(k in root for k in ['.git', 'build', '.gradle', 'app']):
        continue
    for f in files:
        if f.endswith('.md'):
            p = os.path.join(root, f)
            with open(p, 'r', encoding='utf-8', errors='ignore') as fp:
                txt = fp.read()
            for phrase in phrases:
                if phrase.lower() in txt.lower():
                    matches.append((p, phrase))

print(f"Found {len(matches)} matches:")
for p, ph in matches:
    print(f"  {p}: '{ph}'")
