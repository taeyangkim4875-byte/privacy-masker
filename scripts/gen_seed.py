"""
로컬 테스트용 가짜 상담 이력 생성기.
모든 이름·번호는 무작위로 만든 가짜 값이다(카드는 Luhn, 주민번호는 날짜만 유효하게 생성).

    python scripts/gen_seed.py            # docker/mariadb/init/02-seed.sql 다시 생성
    python scripts/gen_seed.py --calls 2000
    python scripts/gen_seed.py --itn-calls 0   # ITN 검증 통화 없이

시드와 함께 이름 정답 파일(masking-core/src/test/resources/eval/seed-names.txt)도 쓴다:
시드 행 순서대로 한 줄에 "행 본문 SHA-1 앞 10자<TAB>그 행에 넣은 이름들(| 구분)". 이름 탐지 Recall·Precision 측정용.
--out 을 바꾸면 --gold-out 도 함께 지정해야 정답 파일을 쓴다(기본 정답 파일과 어긋나지 않게).
"""
import argparse
import hashlib
import random
from datetime import datetime, timedelta
from pathlib import Path

SURNAMES = list("김이박최정강조윤장임한오서신권황안송류전홍고문양손배백허남심노하곽성차주우구") + ["남궁", "황보", "제갈"]
GIVEN = list("민서준우하윤지호예은도현수아태양시연하늘유진건희주원채린서영재민가온다인소율")
BANKS = ["국민은행", "신한은행", "우리은행", "하나은행", "농협", "기업은행", "카카오뱅크"]
INTENTS = ["카드 분실 신고", "결제 취소 문의", "한도 상향 요청", "청구서 주소 변경", "연체 금액 확인", "포인트 사용 문의"]
KOR_DIGIT = "공일이삼사오육칠팔구"


# 지금 만드는 통화·행에 넣은 이름(정답 파일용). 기록만 하고 난수는 쓰지 않으므로 시드 내용은 그대로다
NAME_LOG = []


def logged(n):
    NAME_LOG.append(n)
    return n


def gold(text):
    """이 발화에 실제로 들어간 이름들. 다른 이름 안에서만 나오는 짧은 이름("김민수" 안의 "김민")은 뺀다"""
    found = [n for n in dict.fromkeys(NAME_LOG) if n in text]
    return [n for n in found if not any(m != n and n in m and text.count(n) == text.count(m) for m in found)]


def name(r):
    return logged(r.choice(SURNAMES) + "".join(r.choice(GIVEN) for _ in range(r.choice([1, 2, 2, 2]))))


def phone(r):
    mid, last = r.randint(1000, 9999), r.randint(1000, 9999)
    return r.choice([f"010-{mid}-{last}", f"010{mid}{last}", f"010 {mid} {last}"])


def landline(r):
    return r.choice([f"02-{r.randint(100, 9999)}-{r.randint(1000, 9999)}", f"031-{r.randint(100, 999)}-{r.randint(1000, 9999)}"])


def card(r):
    digits = [r.choice([4, 5, 9])] + [r.randint(0, 9) for _ in range(14)]
    total = 0
    for i, d in enumerate(reversed(digits)):
        n = d * 2 if i % 2 == 0 else d
        total += n - 9 if n > 9 else n
    digits.append((10 - total % 10) % 10)
    s = "".join(map(str, digits))
    sep = r.choice(["-", " ", ""])
    return sep.join(s[i:i + 4] for i in range(0, 16, 4))


def rrn(r):
    birth = datetime(1960, 1, 1) + timedelta(days=r.randint(0, 365 * 45))
    g = (1 if birth.year < 2000 else 3) + r.randint(0, 1)
    return f"{birth:%y%m%d}-{g}{r.randint(0, 999999):06d}"


def account(r):
    return r.choice([
        f"{r.randint(100, 999)}-{r.randint(100, 999)}-{r.randint(100000, 999999)}",
        f"{r.randint(100, 999)}-{r.randint(10, 99)}-{r.randint(100000, 999999)}-{r.randint(1, 9)}",
        f"{r.randint(1000, 9999)}{r.randint(10000000, 99999999)}",
    ])


def email(r, n):
    local = r.choice(["kim", "park", "lee", "choi", "jung", "yoon", "han"]) + str(r.randint(1, 999))
    return f"{local}@{r.choice(['example.com', 'example.co.kr', 'test.example'])}"


def kor_digits(s):
    return "".join(KOR_DIGIT[int(c)] if c.isdigit() else " " for c in s)


def call(r):
    """한 통화의 발화 목록 [(speaker, text)]"""
    agent, cust = name(r), name(r)
    intent = r.choice(INTENTS)
    u = [("AGENT", f"안녕하세요 고객님, 상담원 {agent}입니다. 무엇을 도와드릴까요?"),
         ("CUSTOMER", f"{intent} 때문에 전화드렸어요."),
         ("AGENT", "본인 확인을 위해 성함 말씀해 주시겠어요?")]
    style = r.random()
    if style < 0.4:
        u.append(("CUSTOMER", f"{cust}입니다."))
    elif style < 0.7:
        u.append(("CUSTOMER", f"{cust}이에요."))
    elif style < 0.85:
        u.append(("CUSTOMER", f"성함은 {cust}요."))
    else:
        u.append(("CUSTOMER", f"네 {cust}"))  # 문맥 단서 없음: 규칙 탐지기가 놓치는 사례
    u.append(("AGENT", f"{cust} 고객님 확인 감사합니다. 연락처 확인 부탁드립니다."))
    p = phone(r)
    if r.random() < 0.12:
        u.append(("CUSTOMER", f"{kor_digits(p.replace('-', ' '))} 이요."))  # 한글로 읽은 숫자: ITN 전에는 놓침
    else:
        u.append(("CUSTOMER", f"{p}이요."))
    if r.random() < 0.3:
        u.append(("CUSTOMER", f"회사 번호는 {landline(r)}입니다."))
    if intent in ("카드 분실 신고", "결제 취소 문의", "한도 상향 요청"):
        u.append(("AGENT", "카드 번호 전체를 말씀해 주시겠어요?"))
        u.append(("CUSTOMER", f"{card(r)} 입니다."))
        u.append(("AGENT", "추가 확인을 위해 주민등록번호 앞자리와 뒷자리를 말씀해 주세요."))
        u.append(("CUSTOMER", f"{rrn(r)}이요."))
    if intent in ("결제 취소 문의", "연체 금액 확인"):
        u.append(("CUSTOMER", f"환불은 {r.choice(BANKS)} {account(r)} 계좌로 부탁드려요."))
    if intent == "청구서 주소 변경" or r.random() < 0.2:
        u.append(("CUSTOMER", f"청구서는 이메일 {email(r, cust)}로 받을게요."))
    u.append(("AGENT", f"네 처리되었습니다. 이번 달 결제일은 {r.choice([5, 14, 25])}일입니다. 다른 문의 있으실까요?"))
    u.append(("CUSTOMER", "아니요 감사합니다."))
    return u


def kor_read(s, r):
    """숫자 문자열을 한 자리씩 한글로 읽는다. 그룹 구분은 공백 / '에' / 붙여 읽기 중 하나"""
    groups = [g for g in s.replace(" ", "-").split("-") if g]
    readings = ["".join(KOR_DIGIT[int(c)] for c in g) for g in groups]
    if r.random() < 0.2:
        readings[0] = readings[0].replace("공", "영")  # '영일영'
    if len(groups) >= 3 and r.random() < 0.1:
        readings[1] = groups[1]  # STT 가 일부만 숫자로 낸 경우: '공일공4821칠칠삼육'
        return "".join(readings)
    return r.choice([" ", "에 ", "", "-", ", "]).join(readings)


def itn_call(r):
    """ITN 검증용 통화: 한글로 읽은 번호(정탐)와 숫자처럼 보이지만 개인정보가 아닌 표현(오탐 유도)"""
    agent, cust = name(r), name(r)
    u = [("AGENT", f"안녕하세요 고객님, 상담원 {agent}입니다. 무엇을 도와드릴까요?"),
         ("CUSTOMER", f"{r.choice(INTENTS)} 때문에 전화드렸어요."),
         ("AGENT", "본인 확인을 위해 성함 말씀해 주시겠어요?"),
         ("CUSTOMER", f"{cust}입니다."),
         ("AGENT", f"{cust} 고객님 확인 감사합니다. 연락처 확인 부탁드립니다.")]
    mid, last = r.randint(100, 9999), r.randint(1000, 9999)
    p = f"010-{mid}-{last}"
    if r.random() < 0.2:
        u.append(("CUSTOMER", f"010에 {kor_read(f'{mid}-{last}', r)}{r.choice(['이요.', ' 이요.', '입니다.'])}"))
    else:
        u.append(("CUSTOMER", f"{kor_read(p, r)}{r.choice(['이요.', ' 이요.', '이에요.', '입니다.', ' 이 번호로 주세요.'])}"))
    if r.random() < 0.3:
        u.append(("CUSTOMER", f"회사 번호는 {kor_read(f'02-{r.randint(100, 9999)}-{r.randint(1000, 9999)}', r)} 입니다."))
    pick = r.random()
    if pick < 0.35:
        u.append(("AGENT", "카드 번호 전체를 말씀해 주시겠어요?"))
        u.append(("CUSTOMER", f"{kor_read(card(r).replace(' ', '-'), r)} 입니다."))
    elif pick < 0.65:
        u.append(("AGENT", "주민등록번호 앞자리와 뒷자리를 말씀해 주세요."))
        u.append(("CUSTOMER", f"{kor_read(rrn(r), r)}이요."))
    else:
        u.append(("CUSTOMER", f"환불은 {r.choice(BANKS)} {kor_read(account(r), r)} 계좌로 부탁드려요."))
    # 오탐 유도: 숫자 음절이 들어가지만 개인정보가 아닌 표현(자릿수 단어, 짧은 숫자, 일반 단어)
    u.append(("AGENT", r.choice([
        "본인 확인되셨으면 일번을 눌러주세요.",
        f"이번 달 결제 금액은 {r.choice(['삼십만원', '오만 삼천원', '이십사만 칠천원'])}입니다.",
        "이천이십육년 시월에 가입하셨네요.",
        f"결제일은 매월 {r.choice(['오일', '십사일', '이십오일'])}입니다.",
    ])))
    u.append(("CUSTOMER", r.choice([
        f"예전 번호 010-{r.randint(1000, 9999)}-{r.randint(1000, 9999)} 오늘부터 안 써요.",
        f"010-{r.randint(1000, 9999)}-{r.randint(1000, 9999)} 이 번호로도 연락 주세요.",
        "일일이 이삼일 걸린대요.",
        "이사 가서 주소가 바뀌었어요.",
        "사이에 다른 카드도 있었어요.",
        "일일 한도는 오백만원으로 해 주세요.",
        "공사 중이라 구일 오후에 다시 전화할게요.",
    ])))
    u.append(("AGENT", "네 처리되었습니다. 다른 문의 있으실까요?"))
    u.append(("CUSTOMER", "아니요 감사합니다."))
    return u


BRANCHES = ["강남", "서초", "송파", "노원", "구로", "마포", "신촌", "홍대", "명동", "성수", "강서", "부평"]
# 이름 규칙("성씨 + 1~2음절 + 입니다/이요/고객님")에 걸리는 상품·멘트 말. 기본 예외 사전에 있어야 한다
DICT_TERMS = ["명세서", "선결제", "공과금", "구독료", "위약금", "연체료", "연회비", "원금", "현금", "금액", "이자",
              "안내문", "신분증", "여권", "제품", "서류", "손님", "오류", "문제", "장애"]


def full_name(r):
    """세 글자 이상 이름(예외어와 겹치지 않게)"""
    return logged(r.choice(SURNAMES) + "".join(r.choice(GIVEN) for _ in range(2)))


def dict_call(r):
    """예외 사전 검증용 통화: 이름처럼 보이는 지점명·직함·상품 용어(오탐 유도)와 진짜(가짜) 이름"""
    agent, cust = full_name(r), full_name(r)
    branch = r.choice(BRANCHES)
    u = [("AGENT", f"안녕하세요 {branch}점 고객님, 상담원 {agent}입니다."),
         ("CUSTOMER", f"{r.choice(INTENTS)} 때문에 전화드렸어요."),
         ("AGENT", "본인 확인을 위해 성함 말씀해 주시겠어요?"),
         ("CUSTOMER", f"{cust}입니다."),
         ("AGENT", f"{cust} 고객님 확인 감사합니다.")]
    for term in r.sample(DICT_TERMS, 3):
        u.append((r.choice(["AGENT", "CUSTOMER"]), r.choice([
            f"말씀하신 건 {term}입니다.",
            f"네 {term}이요.",
            f"그건 {term}이고요, 다음 달에 다시 안내드릴게요.",
        ])))
    u.append(("AGENT", r.choice([
        f"{branch}점 지점장님께 {cust} 고객님 건 전달드리겠습니다.",
        f"{cust} 고객님 이번 달 명세서입니다.",
        f"예금주 {cust}이요, 확인했습니다.",
    ])))
    u.append(("CUSTOMER", "네 감사합니다."))
    return u


ROMAN_SURNAME = {"김": "KIM", "이": "LEE", "박": "PARK", "최": "CHOI", "정": "JUNG", "강": "KANG", "조": "CHO",
                 "윤": "YOON", "장": "JANG", "임": "LIM", "한": "HAN", "오": "OH", "서": "SEO", "신": "SHIN",
                 "권": "KWON", "황": "HWANG", "안": "AHN", "송": "SONG", "홍": "HONG", "고": "KO", "문": "MOON"}
ROMAN_GIVEN = ["EUNMIN", "SEOYEON", "MINJUN", "JIHO", "HAYOON", "DOHYUN", "SUA", "TAEYANG", "HANEUL", "YUJIN", "SEO YEON", "MIN JUN"]
SSML_HEAD = ('<?xml version="1.0" encoding="UTF-8"?><speak version="1.0" '
             'xmlns="http://www.w3.org/2001/10/synthesis" xml:lang="ko-KR"><prosody rate="slow">')
SSML_TAIL = '</prosody></speak>'
CONFIRM = ',회원님이 맞으면 1번, 아니면 2번을 눌러주세요. 다시듣기는 별표를 눌러주세요.'


def ssml_rows(r, n_calls):
    """콜봇 TTS 요청 SSML: 실제 운영 화면에서 본 형태를 본뜬 가짜 데이터"""
    rows = []
    golds = []
    for i in range(n_calls):
        call_id = f"B{20261020 + i // 40}-{i:05d}"
        NAME_LOG.clear()
        sur = r.choice([k for k in ROMAN_SURNAME])
        given = "".join(r.choice(GIVEN) for _ in range(2))
        nm = logged(sur + given)
        logged(",".join(nm))
        p = phone(r).replace("-", "").replace(" ", "")
        steps = []
        # 1. 본인 확인: 이름 + 쉼표 + 회원님 / 영문 이름 / 한 글자씩 / 이름만 단독
        style = r.random()
        if style < 0.45:
            steps.append(("CONFIRM_NAME", f"{nm}{CONFIRM}"))
        elif style < 0.65:
            roman = logged(f"{ROMAN_SURNAME[sur]} {r.choice(ROMAN_GIVEN)}")
            steps.append(("CONFIRM_NAME", f"{roman}{CONFIRM}"))
        elif style < 0.85:
            steps.append(("SPELL_NAME", ",".join(nm)))
        else:
            steps.append(("NAME_ONLY", nm))
        # 2. 카드 보유 안내(이름 + 회원님 붙여 쓰기)
        if r.random() < 0.5:
            steps.append(("CARD_LIST", f"{nm}회원님의 카드는 현재 두개 입니다. 안내를 끝까지 들으시고 도난분실한 카드를 모두 "
                                       f"선택하신 후 우물정자를 눌러주세요. 일번,패밀리 카드M_NEW,LOCAL,이번,카드M(구) 하이패스,LOCAL, 입니다."))
        # 3. 전화번호 읽어주기(say-as telephone): 전체 또는 뒤 4자리
        steps.append(("READ_PHONE", f'<say-as interpret-as="telephone">{p}</say-as>'))
        if r.random() < 0.5:
            steps.append(("READ_PHONE_LAST4", f'<say-as interpret-as="telephone">{p[-4:]}</say-as>'))
        # 4. 개인정보 아닌 숫자: 금액·이율, 날짜
        steps.append(("READ_RATE", f'<say-as interpret-as="cardinal">{r.randint(1, 29)}.{r.randint(0, 9)}</say-as>'))
        steps.append(("READ_DATE", f'<say-as format="md" interpret-as="date">{r.randint(1, 12):02d}{r.randint(1, 28):02d}</say-as>'))
        # 5. 개인정보 없는 고정 멘트
        steps.append(("GUIDE", "상세정보를 보실 수 있습니다. 다시듣기는 별표를 눌러주세요."))
        for scen, body in steps:
            rows.append((call_id, scen, SSML_HEAD + body + SSML_TAIL))
            golds.append(gold(body))
    return rows, golds


def sql_str(s):
    return "'" + s.replace("\\", "\\\\").replace("'", "''") + "'"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--calls", type=int, default=300)
    ap.add_argument("--itn-calls", type=int, default=40, help="한글로 읽은 숫자 검증용 통화 수(기존 행과 별도 난수)")
    ap.add_argument("--dict-calls", type=int, default=30, help="예외 사전 검증용 통화 수(기존 행과 별도 난수)")
    ap.add_argument("--seed", type=int, default=20261022)
    ap.add_argument("--out", default=str(Path(__file__).resolve().parent.parent / "docker/mariadb/init/02-seed.sql"))
    ap.add_argument("--gold-out", default=None, help="이름 정답 파일(기본: --out 이 기본값일 때만 eval/seed-names.txt)")
    a = ap.parse_args()
    root = Path(__file__).resolve().parent.parent
    if a.gold_out is None and Path(a.out).resolve() == (root / "docker/mariadb/init/02-seed.sql").resolve():
        a.gold_out = str(root / "masking-core/src/test/resources/eval/seed-names.txt")
    r = random.Random(a.seed)

    rows = []
    g_rows = []
    t = datetime(2026, 10, 1, 9, 0, 0)
    for i in range(a.calls):
        call_id = f"C{20261001 + i // 50}-{i:05d}"
        t += timedelta(minutes=r.randint(1, 40))
        NAME_LOG.clear()
        for j, (spk, text) in enumerate(call(r)):
            g_rows.append(gold(text))
            rows.append((call_id, j + 1, spk, text, (t + timedelta(seconds=j * 9)).strftime("%Y-%m-%d %H:%M:%S")))

    tts = []
    g_tts = []
    for i in range(80):
        NAME_LOG.clear()
        n = name(r)
        tmpl = r.choice([
            f"{n} 고객님, 요청하신 카드가 발송되었습니다.",
            f"{n} 고객님 결제 금액은 {r.randint(1, 90) * 1000}원입니다.",
            f"안녕하세요 {n}님, 등록하신 번호 {phone(r)}로 인증번호를 보냈습니다.",
            "고객센터 운영 시간은 평일 오전 9시부터 오후 6시까지입니다.",
        ])
        g_tts.append(gold(tmpl))
        tts.append((tmpl, r.choice(["IVR", "ALIMTALK", "OUTBOUND"]), (t + timedelta(minutes=i)).strftime("%Y-%m-%d %H:%M:%S")))

    ssml, g_ssml = ssml_rows(r, 150)

    lines = ["-- generated by scripts/gen_seed.py (fake data only)", "SET NAMES utf8mb4;", ""]
    for k in range(0, len(rows), 200):
        chunk = rows[k:k + 200]
        lines.append("INSERT INTO stt_call_history (call_id, seq, speaker, utterance, created_at) VALUES")
        lines.append(",\n".join(f"({sql_str(c)}, {s}, {sql_str(sp)}, {sql_str(tx)}, {sql_str(ts)})" for c, s, sp, tx, ts in chunk) + ";")
    # ITN(한글로 읽은 숫자) 통화: 기존 행이 바뀌지 않도록 별도 난수로 만들어 뒤에 붙인다
    r_itn = random.Random(a.seed + 7)
    itn_rows = []
    g_itn = []
    t3 = datetime(2026, 10, 21, 9, 0, 0)
    for i in range(a.itn_calls):
        k = a.calls + i
        call_id = f"C20261021-{k:05d}"
        t3 += timedelta(minutes=r_itn.randint(1, 40))
        NAME_LOG.clear()
        for j, (spk, text) in enumerate(itn_call(r_itn)):
            g_itn.append(gold(text))
            itn_rows.append((call_id, j + 1, spk, text, (t3 + timedelta(seconds=j * 9)).strftime("%Y-%m-%d %H:%M:%S")))
    if itn_rows:
        lines.append("INSERT INTO stt_call_history (call_id, seq, speaker, utterance, created_at) VALUES")
        lines.append(",\n".join(f"({sql_str(c)}, {s}, {sql_str(sp)}, {sql_str(tx)}, {sql_str(ts)})" for c, s, sp, tx, ts in itn_rows) + ";")
    # 예외 사전 검증 통화: 마찬가지로 별도 난수, 뒤에 붙인다
    r_dict = random.Random(a.seed + 11)
    dict_rows = []
    g_dict = []
    t4 = datetime(2026, 10, 22, 9, 0, 0)
    for i in range(a.dict_calls):
        call_id = f"C20261022-{a.calls + a.itn_calls + i:05d}"
        t4 += timedelta(minutes=r_dict.randint(1, 40))
        NAME_LOG.clear()
        for j, (spk, text) in enumerate(dict_call(r_dict)):
            g_dict.append(gold(text))
            dict_rows.append((call_id, j + 1, spk, text, (t4 + timedelta(seconds=j * 9)).strftime("%Y-%m-%d %H:%M:%S")))
    if dict_rows:
        lines.append("INSERT INTO stt_call_history (call_id, seq, speaker, utterance, created_at) VALUES")
        lines.append(",\n".join(f"({sql_str(c)}, {s}, {sql_str(sp)}, {sql_str(tx)}, {sql_str(ts)})" for c, s, sp, tx, ts in dict_rows) + ";")
    lines.append("INSERT INTO tts_request_log (request_text, channel, requested_at) VALUES")
    lines.append(",\n".join(f"({sql_str(tx)}, {sql_str(ch)}, {sql_str(ts)})" for tx, ch, ts in tts) + ";")
    t2 = datetime(2026, 10, 20, 9, 0, 0)
    for k in range(0, len(ssml), 200):
        chunk = ssml[k:k + 200]
        lines.append("INSERT INTO tts_ssml_log (call_id, scenario, ssml, requested_at) VALUES")
        vals = []
        for j, (c, sc, x) in enumerate(chunk):
            ts = (t2 + timedelta(seconds=(k + j) * 7)).strftime("%Y-%m-%d %H:%M:%S")
            vals.append(f"({sql_str(c)}, {sql_str(sc)}, {sql_str(x)}, {sql_str(ts)})")
        lines.append(",\n".join(vals) + ";")
    Path(a.out).write_text("\n".join(lines) + "\n", encoding="utf-8")
    # 정답 파일: SQL 에 나오는 행 순서(stt 기본 → ITN → 사전 검증 → tts → ssml)와 같게
    golds = g_rows + g_itn + g_dict + g_tts + g_ssml
    assert len(golds) == len(rows) + len(itn_rows) + len(dict_rows) + len(tts) + len(ssml)
    texts = [x[3] for x in rows + itn_rows + dict_rows] + [x[0] for x in tts] + [x[2] for x in ssml]
    if a.gold_out:
        Path(a.gold_out).parent.mkdir(parents=True, exist_ok=True)
        Path(a.gold_out).write_text(
            "# generated by scripts/gen_seed.py: 02-seed.sql 행 순서대로 \"본문 SHA-1 앞 10자<TAB>가짜 이름(| 구분)\"\n"
            + "\n".join(hashlib.sha1(tx.encode("utf-8")).hexdigest()[:10] + "\t" + "|".join(g)
                        for tx, g in zip(texts, golds)) + "\n", encoding="utf-8")
    else:
        print("--out 을 바꿨으므로 이름 정답 파일은 쓰지 않음(--gold-out 으로 지정)")
    print(f"wrote {len(rows)} + {len(itn_rows)} (ITN) + {len(dict_rows)} (dict) utterances / {a.calls} + {a.itn_calls} + {a.dict_calls} calls, {len(tts)} tts rows, {len(ssml)} ssml rows -> {a.out}")


if __name__ == "__main__":
    main()
