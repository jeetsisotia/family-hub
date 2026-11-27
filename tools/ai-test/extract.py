"""Copies the question patterns from MoneyAnswers.kt into Regexes.kt so AiTest.kt can check them."""
import re, sys
src = open(sys.argv[1] if len(sys.argv) > 1 else '../../app/src/main/java/com/familyhub/app/ui/ai/MoneyAnswers.kt').read()
names = ['Q_SPEND', 'Q_TOP', 'Q_EMI', 'Q_CARD', 'Q_SAVE', 'ADDING', 'Q_FAMILY', 'Q_BALANCE', 'Q_DUE', 'Q_UNCAT', 'ADD_CAT', 'SET_BUDGET', 'THEME']
out = ['object R {']
for n in names:
    # One string, or several joined with + across lines.
    m = re.search(r'private val ' + n + r' = (Regex\(\s*"(?:[^"\\]|\\.)*"(?:\s*\+\s*"(?:[^"\\]|\\.)*")*\s*\))', src, re.S)
    out.append('    val ' + n + ' = ' + m.group(1))
out.append('}')
open('Regexes.kt', 'w').write('\n'.join(out) + '\n')
