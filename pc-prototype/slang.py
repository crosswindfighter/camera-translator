# SlangNormalizer.kt'nin Python karsiligi: OPUS'un bilmedigi oyun/sokak argosunu
# ceviriden once modelin anladigi sade Ingilizceye cevirir.
#
# Kurallar Android tarafinda OPUS modeliyle once/sonra test edilerek secildi.
# Burada YENI kural eklenmiyor, sadece ayni kurallar PC'ye tasiniyor.
#
# Java ile Python regex farki: Java degisken uzunluklu geriye bakisa izin verir,
# Python vermez. O kurallarda (?<=X) yerine (X) yakalama grubu kullanildi ve
# eslesen on-ek oldugu gibi geri yaziliyor (asagida pre=True).

import re

IC = re.IGNORECASE

# Hitap sonu: ardindan noktalama ya da metin sonu gelmeli
VOC = r"(?=\s*[.!?,]|\s*$)"


def _r(pattern, replacement, pre=False):
    """pre=True: 1. grup eslesmenin degismeden kalan on-ekidir."""
    return (re.compile(pattern, IC), replacement, pre)


# Google modlarinda da uygulanan dar kurallar
SHARED_RULES = [
    # Bosluk yerine nokta okunmus: "gonna.make it" cumleyi bolup anlami bozuyordu
    _r(r"(?<=(?-i:[a-z]{2}))\.(?=(?-i:[a-z]{2}))(?!(?:com|net|org|info|edu|gov)\b)", " "),
    # Hitaplar
    _r(r"(?<=,\s)(dog|dawg)" + VOC, "dude"),
    _r(r"(?<=,\s)(homes|holmes)" + VOC, "buddy"),
    _r(r"(?<=,\s)ese" + VOC + r"|^ese(?=,)", "man"),
    _r(r"(?<=,\s)(cuz|fam|bruh|brah)" + VOC, "dude"),
    _r(r"\bmy (nigga|nigguh|niggah)\b", "my brother"),
    _r(r"\b(nigga|nigguh|niggah)s\b", "guys"),
    _r(r"\b(nigga|nigguh|niggah)\b", "man"),
    _r(r"\btricked[- ]out\b", "modified"),
    # "Mr. Revolutionary" -> "Bay. Devrimci" (nokta cumleyi boluyordu)
    _r(r"\bMrs\.(?=\s+[A-Z])", "Mrs"),
    _r(r"\bMr\.(?=\s+[A-Z])", "Mr"),
    _r(r"\bDr\.(?=\s+[A-Z])", "Dr"),
    _r(r"\bI got this(?=[,.!?]*$)", "I can handle this"),
    _r(r"\bwhat's crack(?:ing|in)\b'?", "what's up"),
    # Sik OCR hatalari
    _r(r"\b((?:I|you|we|they) (?:don't|didn't) )now\b", "know", True),
    _r(r"(?<=\bgive )ne\b", "me"),
    _r(r"\b((?:do|did|does|can|could|should|would) )I'll\b", "I", True),
    _r(r"\b(?-i:yOu|yoU|YoU)\b", "you"),
    _r(r"\b(?-i:gO)\b", "go"),
    _r(r"\b(?-i:sO)\b", "so"),
    _r(r"(?:^|(?<=[.!?]\s))(?-i:SO)(?= (?-i:[a-z]))", "So"),
    _r(r"'ern\b", "'em"),
    _r(r"\byou'l\b", "you'll"),
    _r(r"\bthey'l\b", "they'll"),
    _r(r"\bwe'l\b", "we'll"),
    _r(r"\baheads up\b", "a heads up"),
    # Kesmesi dusmus "we'll" ("as well be" haric)
    _r(r"(?<!\bas )\bwell(?= (talk|see|be|have|get|go|need|do|make|figure|find|meet|handle|take)\b)", "we'll"),
    _r(r"\b((?:we|you|they|I|he|she) )gpt\b", "got", True),
    _r(r"\bsiting(?= (there|here|around)\b)", "sitting"),
    _r(r"\bthere'[Il]\b", "there'll"),
    _r(r"\bthat'[Il]\b", "that'll"),
    _r(r"\bit'[Il]\b", "it'll"),
    _r(r"\bshe'[Il]\b", "she'll"),
    _r(r"\bhe'[Il]\b", "he'll"),
    # "T poured my heart" -> I poured
    _r(r"(?:^|(?<=[.!?]\s)|(?<=\band\s))(?-i:T)(?= [a-z])(?! (?:shirt|shirts|minus)\b)", "I"),
    # "Were taking a chopper" -> We're
    _r(r"(?:^|(?<=[.!?]\s))(?-i:Were)(?= (?!being\b)[a-z]+ing\b)", "We're"),
    # "a team df agents" -> of ("my bf" etkilenmez)
    _r(r"(?<!\bmy )(?<!\byour )(?<!\bher )(?<!\bhis )(?<!\bthe )(?<!\ba )\b[bd]f(?= [a-z])", "of"),
    _r(r"\bshouldn'?ta\b", "shouldn't have"),
    _r(r"\bwouldn'?ta\b", "wouldn't have"),
    _r(r"\bcouldn'?ta\b", "couldn't have"),
    _r(r"\bmy nig\b", "my brother"),
    _r(r"\b(?-i:Hove)(?= (?:you|ya|him|her|it|this|that)\b)", "I love"),
]

# Yalnizca OPUS icin
RULES = [
    _r(r"\bmy g\b", "my man"),

    # Kaliplar
    _r(r"\band shit\b", "and stuff"),
    _r(r"\bno shit\b", "no kidding"),
    _r(r"\bno cap\b", "no lie"),
    _r(r"\bfive-?o\b|\b5-0\b", "the cops"),
    _r(r"\brepo\b", "repossess"),
    _r(r"\bstrapped\b(?!\s+(in|down|into|to|by|with|on|onto|up|across|at|for|inside|under|together)\b)", "armed"),
    _r(r"\blet's bounce\b", "let's go"),
    _r(r"\bgat\b", "gun"),
    _r(r"\b((?:my|this|that|your|his|her) )whip\b", "car", True),
    _r(r"\bnice ride\b", "nice car"),
    _r(r"\bthe hood\b", "the neighborhood"),
    _r(r"\blet's bail\b", "let's leave"),
    _r(r"\b(ain't|aint|isn't|is not|not) (gonna|going to) be cool\b", "won't be happy"),
    _r(r"\bwon't be cool\b", "won't be happy"),
    _r(r"\blose the (cops|police)\b", "escape the cops"),

    _r(r"^fuck me(?=[.!]*$|, fuck\b)", "Fuck"),
    _r(r"^yo(?=!*$)", "Hey"),
    _r(r"\burgh\b", "ugh"),
    _r(r"!{2,}", "!"),
    _r(r"\?{2,}", "?"),
    _r(r",?\s+err(?=[,.]|$)", ""),
    _r(r"\bI'm straight(?=, (?:fool|man|homie|dude|bro)\b)", "I'm good"),
    _r(r"\bgood looking out\b", "thanks for the help"),
    _r(r"\bslingin[g']? dope\b", "selling dope"),
    _r(r"\bmake my skin crawl\b", "disgust me"),
    _r(r"\bain't this '?bout a bitch\b", "this is fucking unbelievable"),
    _r(r"\btough (?:fucking )?tits\b", "too fucking bad"),
    _r(r"^the (?:fucking )?cheek(?: of it)?(?=[.!]*$)", "What nerve"),
    _r(r"\b((?:that|this|the|you) )fruitcake\b", "lunatic", True),
    _r(r"(?<=\byour )old lady\b", "woman"),
    _r(r"\bnail(?= my (?:wife|girl|girlfriend)\b)", "fuck"),
    _r(r"\bhit a bank\b", "rob a bank"),
    _r(r"\bwe do a store\b", "we rob a store"),
    _r(r"\bknow the score\b", "know how this works"),
    _r(r"\bthe one time(?=[!.]*$)", "the cops"),
    _r(r"\bhang with your ass\b", "hang with you"),
    _r(r"\byour ass don't look\b", "you don't look"),
    _r(r"^fuckin[g']? a(?=[.!])", "Fuck yeah"),
    _r(r"\bfo'? sho\b", "for sure"),
    _r(r"^y'all telling me\b", "Tell me about it"),
    _r(r"\bspidnot\b", "spot"),
    _r(r"\bnizote\b", "note"),
    _r(r"\bwork forces\b", "workforces"),
    _r(r"\bmobile device\b", "portable device"),
    _r(r"\bhog-riding\b", "biker"),

    # Konusma dili kisaltmalari
    _r(r"\bgonna\b", "going to"),
    _r(r"\bwanna\b", "want to"),
    _r(r"\b((?:it|he|she|that|there)'s\s)gotta\b", "got to", True),
    _r(r"(?<=['’]ve\s)gotta\b", "got to"),
    _r(r"(?<=\bhave\s)gotta\b", "got to"),
    _r(r"\bgotta\b", "have to"),
    _r(r"\blemme\b", "let me"),
    _r(r"\bgimme\b", "give me"),
    _r(r"\bdunno\b", "don't know"),
    _r(r"\by'all\b", "you all"),
    _r(r"\bain't got\b", "don't have"),
    _r(r"\bokey[- ]doke(y)?\b", "okay"),
    _r(r"\bwhat kept (?:ya|you)\b", "what took you so long"),
    _r(r"\bcasing the (joint|place)\b", "scouting the place"),
    _r(r"\bcased the (joint|place)\b", "scouted the place"),
    _r(r"\bcase the (joint|place)\b", "scout the place"),
    _r(r"\bcalled shotgun\b", "claimed the front seat"),
    _r(r"\bcall shotgun\b", "claim the front seat"),
    _r(r"\broger that\b", "understood"),
    _r(r"^Roger(?=,?\s+(November|Niner|Alpha|Bravo|Charlie|Delta|Echo|Foxtrot|Tango|Victor|Whiskey|Zulu)\b|[.!]?$)", "Understood"),

    # Oyun logundaki hatalar
    _r(r"\bhomies\b", "buddies"),
    _r(r"\bstoolies\b", "snitches"),
    _r(r"\bstoolie\b", "snitch"),
    _r(r"\brap+elling\b", "climbing down"),
    _r(r"\brap+el\b", "climb down"),
    _r(r"\bget crackin[g']?", "get moving"),
    _r(r"\bhit me up\b", "call me"),
    _r(r"\bblow this (?:joint|place)\b", "get out of here"),
    _r(r"\bconversate\b", "talk"),
    _r(r"\bjudgemental\b", "judgmental"),
    _r(r"\bfire in the hole\b", "take cover"),
    _r(r"\bfinna\b|\bfin'? to\b", "going to"),
    _r(r"\byo'?self\b", "yourself"),
    _r(r"\byo'(?=\s)", "your"),
    _r(r"\bclap(?= (?:me|him|her|them|us|your|some|that|this)\b)(?! (?:your|them|those|some) hands\b)", "shoot"),
    _r(r"\bclapping(?= (?:me|him|her|them|us|your|some|that|this)\b)(?! (?:your|them|those|some) hands\b)", "shooting"),
    _r(r"(?<=\bto )clap(?=[,.!?]*$)", "shoot"),
    _r(r"\b((?:want|wants|wanted|got|get|getting|wasn't|was|be) (?:him |her |me |you |them |'em )?)clapped\b", "shot", True),
    _r(r"\bclapped(?= (?-i:[A-Z]))", "shot"),
    _r(r"\b((?:my|your|our) )crib\b", "place", True),
    _r(r"\bfoot ?path\b", "walkway"),
    _r(r"\bthe go[- ]ahead\b", "permission"),
    _r(r"(?:^|(?<=[.!?]\s))take out(?= the\b(?! (?:trash|garbage)\b)| (?-i:[A-Z]))", "Eliminate"),
    _r(r"^run(?=[.!]*$)", "Run away"),
    _r(r"\bcase board\b", "evidence board"),
    _r(r"\bbellyaching\b", "complaining"),
    _r(r"\bright up (?:your|my|his|her|our) alley\b", "perfect for you"),
    _r(r"\bcracking it\b", "solving it"),
    _r(r"\bbrothers in arms\b", "comrades"),
    _r(r"\bwent off the deep end\b", "went crazy"),
    _r(r"\bgot the boot\b", "got fired"),
    _r(r"\bdeputy(?= (?-i:[A-Z]))", "Officer"),
    _r(r"(?:^|(?<=[.!?]\s))'?sup\b", "What's up"),
]

# Kekeleme: "p-p-pretend", "A-A-And" -> pretend, And (OPUS donguye giriyordu)
STUTTER = re.compile(r"\b([A-Za-z])-(?:\1-)*(?=\1)", IC)


def _keep_case(original, replacement):
    """"Dog" -> "Dude", "dog" -> "dude": cumle basi buyuk harf korunsun."""
    if original[:1].isupper():
        return replacement[:1].upper() + replacement[1:]
    return replacement


def _apply(rules, text):
    for rx, replacement, pre in rules:
        def sub(m, replacement=replacement, pre=pre):
            prefix = m.group(1) if pre else ""
            if prefix is None:
                prefix = ""
            core = m.group(0)[len(prefix):]
            return prefix + _keep_case(core, replacement)
        text = rx.sub(sub, text)
    return text


def normalize_shared(text):
    return _apply(SHARED_RULES, text)


def normalize(text):
    return _apply(RULES, normalize_shared(STUTTER.sub("", text)))
