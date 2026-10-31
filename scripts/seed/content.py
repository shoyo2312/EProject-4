"""Static banks for the demo seed: personas, captions, comments, chat lines, report reasons.

Everything here is fiction. Keep every line free of the terms in
libs/moderation-lib/src/main/resources/moderation/blocklist-vi.txt (test_content.py checks).
"""

# ---------------------------------------------------------------------------------------------
# Personas — 16 creators, 34 regulars, 50 viewers. PERSONAS[0] is the account the demo signs in as.
# ---------------------------------------------------------------------------------------------

def _p(username, display_name, bio, role, topics, **flags):
    return {
        "username": username, "display_name": display_name, "bio": bio, "role": role,
        "topics": topics,
        "private": flags.get("private", False),
        "keyword_filter": flags.get("keyword_filter", False),
        "spam": flags.get("spam", False),
        "temp_ban": flags.get("temp_ban", False),
    }


PERSONAS = [
    # creators
    _p("minhanh.vlog", "Minh Anh", "Đi ăn khắp Sài Gòn, review thật lòng. Hợp tác: inbox nhé.", "creator", ["food", "drinks", "vietnam"]),
    _p("bepcuatrang", "Bếp của Trang", "Nấu ăn mỗi ngày cho gia đình nhỏ. Công thức đơn giản, ai cũng làm được.", "creator", ["food", "home"], keyword_filter=True),
    _p("hieu.travel", "Hiếu Đi Đâu", "Balo và máy quay. 34/63 tỉnh thành. Tiếp theo: Hà Giang mùa lúa.", "creator", ["travel", "nature", "vietnam"]),
    _p("gymvoituan", "Gym Với Tuấn", "HLV cá nhân. Tập đúng kỹ thuật, không cần tập nhiều.", "creator", ["fitness", "sports"]),
    _p("nhadance.studio", "Nhà Dance Studio", "Lớp nhảy hiện đại quận 3. Cover mỗi tuần một bài.", "creator", ["dance", "music"]),
    _p("meocuabo", "Mèo Của Bơ", "Bơ là mèo tam thể 3 tuổi, thích ngủ trên bàn phím.", "creator", ["pets", "animals"]),
    _p("linh.outfit", "Linh Outfit", "Phối đồ đi học đi làm dưới 500k. Mặc đẹp không cần đắt.", "creator", ["fashion", "art"]),
    _p("saigon.by.night", "Sài Gòn By Night", "Quay thành phố sau 10 giờ đêm. Mọi cảnh đều quay bằng điện thoại.", "creator", ["night", "cars", "vietnam"]),
    # regulars
    _p("quang.codes", "Quang Codes", "Sinh viên năm 3 CNTT. Học code buổi tối, đi làm thêm buổi sáng.", "regular", ["study", "tech"]),
    _p("thuhuong.study", "Thu Hương", "Study with me. Mục tiêu: IELTS 7.5 trước hè.", "regular", ["study", "home"]),
    _p("baoanh.art", "Bảo Anh vẽ", "Màu nước và chì. Nhận vẽ chân dung theo yêu cầu.", "regular", ["art", "home"]),
    _p("khoa.skate", "Khoa Skate", "Trượt ván ở công viên 23/9 mỗi chiều.", "regular", ["sports", "night"]),
    _p("ngocmai.yoga", "Ngọc Mai Yoga", "Yoga buổi sáng, 20 phút mỗi ngày là đủ.", "regular", ["fitness", "nature"]),
    _p("duc.moto", "Đức Moto", "Chạy xe xuyên Việt lần 2. Xe cũ, đường dài.", "regular", ["cars", "travel"]),
    _p("hanoi.coffee", "Hà Nội Cà Phê", "Mỗi ngày một quán. Cà phê trứng là chân ái.", "regular", ["drinks", "food"]),
    _p("vy.garden", "Vy Làm Vườn", "Vườn sân thượng 20m2. Trồng rau, nuôi ong, ngắm hoa.", "regular", ["garden", "nature"]),
    _p("phuc.drone", "Phúc Flycam", "Quay flycam cảnh Việt Nam từ trên cao.", "regular", ["nature", "travel", "vietnam"]),
    _p("tuananh.guitar", "Tuấn Anh Guitar", "Fingerstyle mỗi tối. Nhận dạy online.", "regular", ["music"]),
    _p("mebimsua", "Mẹ Bỉm Sữa", "Hai bé, một chồng, một bếp. Chia sẻ đời thường.", "regular", ["kids", "home", "food"]),
    _p("lam.nguyen.tech", "Lâm Nguyễn", "Mở hộp đồ công nghệ, in 3D, robot tự chế.", "regular", ["tech", "cars"]),
    _p("trinh.makeup", "Trinh Makeup", "Makeup đi học, đi tiệc, đi chơi. Da dầu cũng được.", "regular", ["fashion"]),
    _p("danang.local", "Đà Nẵng Local", "Người Đà Nẵng kể chuyện Đà Nẵng.", "regular", ["travel", "food", "vietnam"]),
    _p("hoang.boxing", "Hoàng Boxing", "Tập boxing phong trào, không thi đấu.", "regular", ["fitness", "sports"]),
    _p("my.linh.piano", "Mỹ Linh Piano", "Cover piano nhạc Việt.", "regular", ["music"]),
    _p("chu.bonsai", "Chú Bonsai", "Về hưu rồi, chăm cây cho vui.", "regular", ["garden", "animals"], temp_ban=True),
    # viewers
    _p("hoa.pham.92", "Hoa Phạm", "Xem video lúc rảnh.", "viewer", ["food"]),
    _p("binh.minh.07", "Bình Minh", "Thích mèo và cà phê.", "viewer", ["pets", "drinks"]),
    _p("thanh.tung.dev", "Thanh Tùng", "Dev backend. Hay thức khuya.", "viewer", ["tech", "study"]),
    _p("kim.ngan.123", "Kim Ngân", "Sinh viên Ngoại thương.", "viewer", ["fashion", "study"]),
    _p("le.van.hau", "Lê Văn Hậu", "Bóng đá là đam mê.", "viewer", ["sports"]),
    _p("thao.nguyen.hn", "Thảo Nguyên", "Hà Nội. Mưa. Trà.", "viewer", ["drinks", "nature"], private=True),
    _p("duy.khanh.sg", "Duy Khánh", "Sài Gòn chạy bộ.", "viewer", ["fitness"]),
    _p("mai.huong.art", "Mai Hương", "Thích tranh và gốm.", "viewer", ["art"]),
    _p("trung.kien.88", "Trung Kiên", "Xem cho vui.", "viewer", ["cars", "night"]),
    _p("ngoc.anh.travel", "Ngọc Ánh", "Đi đâu cũng được, miễn là đi.", "viewer", ["travel"]),
    _p("hong.nhung.kids", "Hồng Nhung", "Mẹ của Bin.", "viewer", ["kids", "home"]),
    _p("quoc.bao.music", "Quốc Bảo", "Nghe nhạc cả ngày.", "viewer", ["music", "dance"]),
    _p("tuyet.nhi.09", "Tuyết Nhi", "Học sinh 12.", "viewer", ["study", "dance"]),
    _p("van.anh.garden", "Vân Anh", "Trồng cây ban công.", "viewer", ["garden"]),
    _p("minh.tri.photo", "Minh Trí", "Chụp ảnh đường phố.", "viewer", ["night", "art"]),
    _p("thu.trang.yoga", "Thu Trang", "Yoga và thiền.", "viewer", ["fitness", "nature"]),
    _p("gia.huy.tech", "Gia Huy", "Mê đồ công nghệ.", "viewer", ["tech"]),
    _p("phuong.linh.food", "Phương Linh", "Ăn là chính.", "viewer", ["food", "drinks"]),
    _p("hai.dang.moto", "Hải Đăng", "Xe máy và đường dài.", "viewer", ["cars", "travel"]),
    _p("yen.nhi.pets", "Yến Nhi", "Hai chó một mèo.", "viewer", ["pets", "animals"]),
    _p("dinh.phong.sport", "Đình Phong", "Bóng rổ cuối tuần.", "viewer", ["sports"]),
    _p("khanh.linh.vn", "Khánh Linh", "Yêu Việt Nam.", "viewer", ["vietnam", "travel"], private=True),
    _p("tan.phat.deal", "Tấn Phát Deal", "Săn deal mỗi ngày, giá tốt nhất thị trường.", "viewer", ["tech", "fashion"], spam=True),
    _p("bich.ngoc.home", "Bích Ngọc", "Nhà nhỏ, nhiều cây.", "viewer", ["home", "garden"]),
    _p("anh.tuan.night", "Anh Tuấn", "Cú đêm chính hiệu.", "viewer", ["night", "music"]),
    # creators (batch 2)
    _p("an.nhien.kitchen", "An Nhiên Kitchen", "Bếp nhỏ, món ngon. Mỗi tuần một món mới cho cả nhà.", "creator", ["food", "drinks"]),
    _p("tuan.wanderer", "Tuấn Wanderer", "Đi bụi 3 năm, 12 nước. Quay bằng một máy cũ và một chiếc balo.", "creator", ["travel", "nature"]),
    _p("coach.ha.fit", "Coach Hà", "HLV thể hình nữ. Tập để khoẻ, không tập để gầy.", "creator", ["fitness", "sports"]),
    _p("beatbox.long", "Long Beatbox", "Beatbox và nhạc cụ đường phố. Diễn tối thứ 7 ở phố đi bộ.", "creator", ["music", "dance"]),
    _p("cho.corgi.bun", "Corgi Bún", "Bún là corgi 2 tuổi, ham ăn và sợ mưa.", "creator", ["pets", "animals"]),
    _p("workshop.gom", "Workshop Gốm", "Xưởng gốm nhỏ ở Bát Tràng. Lớp cuối tuần cho người mới.", "creator", ["art", "home"]),
    _p("thanh.city.walk", "Thành City Walk", "Đi bộ qua từng con phố, kể chuyện từng góc nhà.", "creator", ["night", "vietnam", "cars"]),
    _p("gia.dinh.nho", "Gia Đình Nhỏ", "Ba mẹ và hai nhóc. Cuối tuần là phải ra ngoài chơi.", "creator", ["kids", "home", "garden"]),
    # regulars (batch 2)
    _p("hung.react", "Hưng React", "Frontend dev. Thích tối giản và cà phê đen.", "regular", ["tech", "study"]),
    _p("nhi.bujo", "Nhi Bullet Journal", "Ghi chép và lên kế hoạch mỗi tối. Study vlog nhẹ nhàng.", "regular", ["study", "art"]),
    _p("quan.street.photo", "Quân Street", "Ảnh và video đường phố, chủ yếu quay lúc trời mưa.", "regular", ["night", "art", "vietnam"]),
    _p("trang.runner", "Trang Chạy Bộ", "Từ 0 lên 21km trong 8 tháng. Chạy chậm, chạy đều.", "regular", ["fitness", "nature"]),
    _p("bao.surf.muine", "Bảo Surf", "Lướt sóng Mũi Né mùa gió. Dạy kèm cho người mới.", "regular", ["sports", "travel"]),
    _p("oto.cu.giare", "Ô Tô Cũ Giá Rẻ", "Review xe cũ dưới 500 triệu. Nói thật, không nhận quảng cáo.", "regular", ["cars", "tech"]),
    _p("tiem.tra.nho", "Tiệm Trà Nhỏ", "Trà và bánh ở một con hẻm quận 1. Mở từ 7 giờ sáng.", "regular", ["drinks", "food"]),
    _p("ong.mat.dalat", "Ong Mật Đà Lạt", "Nuôi ong và trồng hoa ở Đà Lạt. Mật thật, hoa thật.", "regular", ["garden", "nature"]),
    _p("dj.mia", "DJ Mia", "Chơi nhạc ở các quán quận 1. Set mới mỗi thứ 6.", "regular", ["music", "night"]),
    _p("be.sua.mum", "Mẹ Bé Sữa", "Nhật ký nuôi con lần đầu, sai đâu sửa đó.", "regular", ["kids", "home"]),
    _p("robot.club.hcm", "Robot Club HCM", "CLB robot của sinh viên Bách Khoa. Thi đấu và tự chế.", "regular", ["tech"]),
    _p("vintage.by.my", "Vintage By My", "Đồ si tuyển chọn, phối theo phong cách những năm 90.", "regular", ["fashion", "art"]),
    _p("hue.xua", "Huế Xưa", "Người Huế kể chuyện Huế. Ăn, đi, và nghe.", "regular", ["vietnam", "food", "travel"]),
    _p("muay.thai.vinh", "Muay Thái Vinh", "Tập Muay Thái 4 năm. Phong trào thôi, không thi đấu.", "regular", ["fitness", "sports"]),
    _p("sax.tung", "Tùng Saxophone", "Cover nhạc xưa bằng saxophone mỗi tối.", "regular", ["music"]),
    _p("co.ba.vuon", "Cô Ba Vườn", "Vườn trái cây miền Tây. Mùa nào thức nấy.", "regular", ["garden", "animals", "food"]),
    _p("minh.hoa.diy", "Minh Hoa DIY", "Tự làm đồ trang trí nhà từ đồ cũ.", "regular", ["home", "art"]),
    # viewers (batch 2)
    _p("lan.anh.food", "Lan Anh", "Thích ăn ngon, lười nấu.", "viewer", ["food"]),
    _p("tien.dat.gym", "Tiến Đạt", "Gym 5 buổi một tuần.", "viewer", ["fitness"]),
    _p("my.duyen.cafe", "Mỹ Duyên", "Cà phê sữa đá mỗi sáng.", "viewer", ["drinks", "home"]),
    _p("van.khanh.code", "Văn Khánh", "Sinh viên CNTT năm 2.", "viewer", ["tech", "study"]),
    _p("thu.ha.travel", "Thu Hà", "Mỗi năm một chuyến xa.", "viewer", ["travel", "nature"]),
    _p("duc.anh.ball", "Đức Anh", "Bóng rổ và bóng đá.", "viewer", ["sports"]),
    _p("ha.my.pets", "Hà My", "Nhà có ba con mèo.", "viewer", ["pets", "animals"]),
    _p("nam.phong.car", "Nam Phong", "Mê xe từ bé.", "viewer", ["cars", "tech"]),
    _p("bich.tram.art", "Bích Trâm", "Vẽ cho vui cuối tuần.", "viewer", ["art"]),
    _p("quynh.nhu.dance", "Quỳnh Như", "Nhảy ở lớp, xem ở nhà.", "viewer", ["dance", "music"]),
    _p("khoi.nguyen.night", "Khôi Nguyên", "Thức khuya xem video.", "viewer", ["night", "music"]),
    _p("ngoc.han.kids", "Ngọc Hân", "Mẹ của hai bé gái.", "viewer", ["kids", "home"]),
    _p("thanh.son.garden", "Thanh Sơn", "Trồng rau ban công.", "viewer", ["garden"]),
    _p("hai.yen.fashion", "Hải Yến", "Thích đồ đơn giản.", "viewer", ["fashion"]),
    _p("trong.nhan.study", "Trọng Nhân", "Ôn thi đại học.", "viewer", ["study"]),
    _p("thuy.tien.yoga", "Thuỷ Tiên", "Yoga buổi tối.", "viewer", ["fitness", "nature"]),
    _p("cong.minh.drone", "Công Minh", "Thích cảnh quay từ trên cao.", "viewer", ["nature", "travel"]),
    _p("phuong.thao.vn", "Phương Thảo", "Yêu quê hương.", "viewer", ["vietnam", "food"]),
    _p("anh.khoa.tech", "Anh Khoa", "Theo dõi đồ công nghệ mới.", "viewer", ["tech"]),
    _p("kieu.trinh.drinks", "Kiều Trinh", "Trà sữa là chân ái.", "viewer", ["drinks"]),
    _p("manh.cuong.moto", "Mạnh Cường", "Phượt cuối tuần.", "viewer", ["cars", "travel"]),
    _p("linh.chi.home", "Linh Chi", "Dọn nhà là liệu pháp.", "viewer", ["home"]),
    _p("hoang.long.sport", "Hoàng Long", "Xem thể thao mỗi tối.", "viewer", ["sports", "fitness"]),
    _p("thanh.thao.music", "Thanh Thảo", "Nghe nhạc không lời.", "viewer", ["music"]),
    _p("gia.bao.animals", "Gia Bảo", "Xem video thú cưng cho vui.", "viewer", ["animals", "pets"]),
    # batch 3
    # creators (batch 3)
    _p("kien.cafe.racer", "Kiên Cafe Racer", "Độ xe cổ kiểu cafe racer trong gara nhỏ. Mỗi tuần một món đồ chơi mới.", "creator", ["cars", "tech"]),
    _p("hoa.sketchbook", "Hoa Sketchbook", "Vẽ ký hoạ mỗi sáng ở quán cà phê. Từng nét bút, từng góc phố.", "creator", ["art", "study"]),
    _p("bin.va.me", "Bin Và Mẹ", "Mẹ và Bin 4 tuổi. Trò chơi, đồ thủ công và bữa ăn cho bé.", "creator", ["kids", "home"]),
    _p("tech.cua.nam", "Tech Của Nam", "Đánh giá điện thoại và đồ công nghệ giá mềm cho sinh viên.", "creator", ["tech", "study"]),
    _p("vuon.ban.cong", "Vườn Ban Công", "Biến ban công 6m2 thành vườn rau. Hướng dẫn từng bước cho người mới.", "creator", ["garden", "home"]),
    _p("phodem.review", "Phố Đêm Review", "Đi ăn đêm khắp thành phố. Quán nào mở sau 11 giờ là có mặt.", "creator", ["night", "food", "vietnam"]),
    _p("tram.pets.club", "Trâm Pets Club", "Chăm chó mèo đúng cách. Mẹo nhỏ từ bác sĩ thú y quen.", "creator", ["pets", "animals"]),
    _p("rung.xanh.minh", "Minh Rừng Xanh", "Quay động vật hoang dã ở các vườn quốc gia Việt Nam.", "creator", ["animals", "nature", "vietnam"]),
    _p("phuong.bakes", "Phương Bakes", "Bánh ngọt tại nhà, lò nướng nhỏ cũng làm được. Công thức ghi rõ từng bước.", "creator", ["food", "drinks"]),
    _p("hoc.cung.tam", "Học Cùng Tâm", "Mẹo ôn thi, cách ghi chép và giữ động lực học mỗi ngày.", "creator", ["study"]),
    # regulars (batch 3)
    _p("duyen.dance.cover", "Duyên Dance Cover", "Cover nhảy các bài đang hot, có hướng dẫn chậm từng động tác.", "regular", ["dance"]),
    _p("minh.cajon", "Minh Cajon", "Chơi cajon và hát nhẹ nhàng mỗi cuối tuần ở sân nhà.", "regular", ["music"]),
    _p("kiet.calisthenics", "Kiệt Calisthenics", "Tập xà đơn ngoài công viên. Từ 3 cái lên 20 cái sau 5 tháng.", "regular", ["fitness", "sports"]),
    _p("lan.huong.nau", "Lan Hương Nấu", "Cơm nhà truyền thống như mẹ nấu. Món nào cũng có mẹo riêng.", "regular", ["food", "home"]),
    _p("hai.au.dulich", "Hải Âu Du Ký", "Ghi lại các chuyến đi bằng tàu hoả và xe khách khắp ba miền.", "regular", ["travel", "vietnam"]),
    _p("thao.nail.art", "Thảo Nail", "Vẽ móng theo mùa. Mẫu đơn giản, tự làm tại nhà được.", "regular", ["fashion", "art"]),
    _p("phong.co.khi", "Phong Cơ Khí", "Sửa xe máy và tự chế đồ trong xưởng nhỏ của bố.", "regular", ["cars", "tech"]),
    _p("baotran.docsach", "Bảo Trân Đọc Sách", "Mỗi tuần một cuốn sách. Tóm tắt ngắn gọn cho người bận rộn.", "regular", ["study"]),
    _p("viet.da.bong", "Việt Đá Bóng", "Đá bóng phủi cuối tuần. Quay lại những pha bóng đẹp của đội.", "regular", ["sports"]),
    _p("maichi.tra", "Mai Chi Trà", "Pha trà theo mùa, cắm hoa và dọn bàn trà nhỏ trong nhà.", "regular", ["drinks", "home"]),
    _p("lan.pho.co", "Lan Phố Cổ", "Dạo phố cổ Hà Nội lúc chiều muộn. Đèn lên là đẹp nhất.", "regular", ["vietnam", "night"]),
    _p("cuong.cau.ca", "Cường Câu Cá", "Câu cá hồ cuối tuần. Cá nhỏ thì thả, cá to thì khoe.", "regular", ["animals", "nature"]),
    _p("hagiang.loop", "Hà Giang Loop", "Cung đường Hà Giang qua từng mùa. Hoa tam giác mạch tháng 11.", "regular", ["travel", "nature"]),
    _p("nhung.baby.diary", "Nhung Baby Diary", "Nhật ký bé Su 8 tháng: ăn dặm, tập bò, tập cười.", "regular", ["kids"]),
    _p("dat.python", "Đạt Python", "Học Python từ số 0. Mỗi video một bài tập nhỏ.", "regular", ["tech", "study"]),
    _p("thuy.hoa.tuoi", "Thuỷ Hoa Tươi", "Cắm hoa tươi và trồng hoa cắt cành ở sân sau.", "regular", ["garden", "art"]),
    _p("tien.huan.cho", "Tiến Huấn Luyện Chó", "Dạy chó ngồi, bắt tay, đi dạo không kéo dây. Kiên nhẫn là chính.", "regular", ["pets", "animals"]),
    _p("yen.sneaker", "Yên Sneaker", "Giày thể thao và cách phối với đồ đi học. Giặt giày đúng cách.", "regular", ["fashion"]),
    _p("khai.bongchuyen", "Khải Bóng Chuyền", "Bóng chuyền bãi biển và trong nhà. Đội phong trào quận 7.", "regular", ["sports", "fitness"]),
    _p("chilan.xoi", "Chị Lan Xôi", "Bán xôi sáng ở đầu ngõ. Quay cách nấu xôi dẻo và ngon.", "regular", ["food", "vietnam"]),
    _p("nam.cinematic", "Nam Cinematic", "Quay phim ngắn buổi tối bằng điện thoại. Ánh đèn thành phố là nhân vật chính.", "regular", ["art", "night"]),
    _p("hoasen.thien", "Hoa Sen Thiền", "Thiền và yoga nhẹ cho người mới. Buổi sáng sớm ngoài vườn.", "regular", ["nature", "fitness"]),
    _p("tusach.nho", "Tủ Sách Nhỏ", "Chọn sách thiếu nhi và đọc to cho các bé nghe mỗi tối.", "regular", ["study", "kids"]),
    _p("vinh.xe.tai", "Vĩnh Xe Tải", "Tài xế đường dài, kể chuyện quốc lộ và những quán cơm ven đường.", "regular", ["cars", "vietnam"]),
    _p("diep.makeup.nhe", "Diệp Make-up Nhẹ", "Trang điểm tự nhiên cho đi học, đi làm. Dưới 10 phút.", "regular", ["fashion"]),
    _p("son.hiphop", "Sơn Hiphop", "Nhảy hiphop và freestyle trong phòng tập. Nhận lớp cho bạn nhỏ.", "regular", ["dance", "music"]),
    _p("quyen.sinhto", "Quyên Sinh Tố", "Sinh tố và nước ép cho người tập gym. Ít đường, nhiều trái cây.", "regular", ["drinks", "fitness"]),
    _p("trung.thuysinh", "Trung Thuỷ Sinh", "Hồ cá thuỷ sinh nhỏ trên bàn làm việc. Chăm cá và rêu mỗi ngày.", "regular", ["animals", "home", "pets"]),
    _p("nga.noithat", "Nga Nội Thất", "Sắp xếp nhà nhỏ cho gọn và đẹp. Ý tưởng cho căn hộ 30m2.", "regular", ["home", "art"], temp_ban=True),
    _p("lam.bep.nho", "Lâm Bếp Nhỏ", "Nấu cho một người ăn, món nhanh gọn cho dân văn phòng.", "regular", ["food", "drinks"]),
    # viewers (batch 3)
    _p("bao.ngoc.food", "Bảo Ngọc", "Thích xem nấu ăn.", "viewer", ["food"]),
    _p("dang.khoa.run", "Đăng Khoa", "Chạy bộ buổi sáng.", "viewer", ["fitness", "sports"]),
    _p("kim.chi.dance", "Kim Chi", "Học nhảy cuối tuần.", "viewer", ["dance"]),
    _p("tuan.hung.cars", "Tuấn Hưng", "Xem xe, đọc review.", "viewer", ["cars"]),
    _p("lan.phuong.art", "Lan Phương", "Thích màu nước.", "viewer", ["art"]),
    _p("thanh.huyen.kids", "Thanh Huyền", "Mẹ của bé Na.", "viewer", ["kids"]),
    _p("quang.vinh.tech", "Quang Vinh", "Mê gadget.", "viewer", ["tech"]),
    _p("my.hanh.study", "Mỹ Hạnh", "Ôn IELTS.", "viewer", ["study"]),
    _p("trung.hieu.night", "Trung Hiếu", "Cú đêm.", "viewer", ["night"]),
    _p("nhu.y.garden", "Như Ý", "Có một chậu sen đá.", "viewer", ["garden"]),
    _p("duc.thinh.pets", "Đức Thịnh", "Nuôi một chú chó.", "viewer", ["pets"]),
    _p("ngan.ha.animals", "Ngân Hà", "Thích động vật hoang dã.", "viewer", ["animals", "nature"]),
    _p("hoang.nam.home", "Hoàng Nam", "Thích đồ nội thất gỗ.", "viewer", ["home"]),
    _p("tu.anh.music", "Tú Anh", "Nghe nhạc mọi lúc.", "viewer", ["music"]),
    _p("khanh.van.fashion", "Khánh Vân", "Thích đồ vintage.", "viewer", ["fashion", "art"]),
    _p("van.thanh.drinks", "Vân Thanh", "Mê trà đào cam sả.", "viewer", ["drinks"]),
    _p("huu.phuoc.sport", "Hữu Phước", "Xem bóng đá đêm khuya.", "viewer", ["sports", "night"]),
    _p("thuy.linh.travel", "Thuỳ Linh", "Dành dụm đi du lịch.", "viewer", ["travel"]),
    _p("minh.chau.vn", "Minh Châu", "Yêu ẩm thực Việt.", "viewer", ["vietnam", "food"]),
    _p("nguyen.hai.nature", "Nguyên Hải", "Thích cảnh núi rừng.", "viewer", ["nature", "travel"]),
    _p("bao.long.gym", "Bảo Long", "Tập gym mới được 3 tháng.", "viewer", ["fitness"]),
    _p("diem.my.cook", "Diễm My", "Học nấu mấy món đơn giản.", "viewer", ["food", "home"]),
    _p("tan.dung.moto", "Tấn Dũng", "Mê xe phân khối lớn.", "viewer", ["cars"]),
    _p("thu.thuy.kids", "Thu Thuỷ", "Mẹ hai bé trai.", "viewer", ["kids", "home"]),
    _p("quoc.khanh.code", "Quốc Khánh", "Học lập trình web.", "viewer", ["tech", "study"]),
    _p("phuong.vy.dance", "Phương Vy", "Thích nhảy cover.", "viewer", ["dance", "music"]),
    _p("hung.dung.night", "Hùng Dũng", "Đi làm ca đêm.", "viewer", ["night", "food"]),
    _p("bich.hang.garden", "Bích Hằng", "Trồng hoa hồng.", "viewer", ["garden", "nature"]),
    _p("cam.tu.pets", "Cẩm Tú", "Hai bé mèo, một bé thỏ.", "viewer", ["pets", "animals"]),
    _p("trong.hieu.art", "Trọng Hiếu", "Học vẽ digital.", "viewer", ["art", "tech"]),
    _p("lien.huong.drinks", "Liên Hương", "Cà phê muối mỗi chiều.", "viewer", ["drinks"]),
    _p("viet.anh.sport", "Việt Anh", "Cầu lông cuối tuần.", "viewer", ["sports"]),
    _p("thanh.nga.fashion", "Thanh Nga", "Thích đồ đơn giản, dễ phối.", "viewer", ["fashion"]),
    _p("duong.minh.study", "Dương Minh", "Ôn thi cuối kỳ.", "viewer", ["study"]),
    _p("hanh.nguyen.travel", "Hạnh Nguyên", "Thích phượt núi.", "viewer", ["travel", "vietnam"]),
    _p("khoa.nam.music", "Khoa Nam", "Chơi guitar tay ngang.", "viewer", ["music"]),
    _p("my.tam.home", "Mỹ Tâm", "Thích sắp xếp nhà cửa.", "viewer", ["home", "garden"]),
    _p("tuan.cuong.night", "Tuấn Cường", "Thích Sài Gòn về đêm.", "viewer", ["night", "vietnam"]),
    _p("giang.thanh.fit", "Giang Thanh", "Yoga mỗi sáng.", "viewer", ["fitness", "nature"]),
    _p("hoai.thu.kids", "Hoài Thu", "Xem video dạy con.", "viewer", ["kids"]),
    _p("nhat.minh.tech", "Nhật Minh", "Theo dõi đồ công nghệ.", "viewer", ["tech", "cars"]),
    _p("thao.vy.food", "Thảo Vy", "Hay đi ăn vặt.", "viewer", ["food", "drinks"]),
    _p("quang.huy.sport", "Quang Huy", "Chơi bóng rổ.", "viewer", ["sports", "fitness"]),
    _p("lan.nhi.animals", "Lan Nhi", "Xem video thú cưng cho vui.", "viewer", ["animals"]),
    _p("khanh.duy.cars", "Khánh Duy", "Thích xe điện.", "viewer", ["cars", "tech"]),
    _p("ngoc.diep.dance", "Ngọc Diệp", "Nhảy aerobic buổi sáng.", "viewer", ["dance", "fitness"]),
    _p("phat.tai.vn", "Phát Tài", "Thích cảnh làng quê Việt.", "viewer", ["vietnam", "nature"]),
    _p("thuc.quyen.art", "Thục Quyên", "Thích tranh sơn dầu.", "viewer", ["art"], private=True),
    _p("anh.thu.drinks", "Anh Thư", "Pha cà phê tại nhà.", "viewer", ["drinks", "home"]),
    _p("trieu.vy.study", "Triệu Vy", "Làm bài tập lúc nửa đêm.", "viewer", ["study", "night"]),
    _p("hong.phuc.garden", "Hồng Phúc", "Mê cây cảnh mini.", "viewer", ["garden", "home"]),
    _p("dai.nghia.pets", "Đại Nghĩa", "Chó poodle một tuổi.", "viewer", ["pets"]),
    _p("sale.soc.moingay", "Sale Sốc Mỗi Ngày", "Săn sale mỗi ngày, mua một tặng một, giá rẻ nhất thị trường.", "viewer", ["fashion", "tech"], spam=True),
    _p("phuong.nam.music", "Phương Nam", "Thích nhạc acoustic.", "viewer", ["music", "night"]),
    _p("bach.duong.travel", "Bạch Dương", "Mơ đi hết 63 tỉnh thành.", "viewer", ["travel", "vietnam"]),
    _p("my.le.fashion", "Mỹ Lệ", "Đang tập phối đồ.", "viewer", ["fashion"], private=True),
    _p("tien.long.sport", "Tiến Long", "Xem đua xe và bóng đá.", "viewer", ["sports", "cars"]),
    _p("thuan.an.animals", "Thuận An", "Mê chim và cá cảnh.", "viewer", ["animals", "pets"]),
    _p("khanh.ngoc.kids", "Khánh Ngọc", "Chị gái của hai em nhỏ.", "viewer", ["kids"]),
    _p("tuan.kiet.night", "Tuấn Kiệt", "Thích nghe nhạc khi trời mưa.", "viewer", ["night", "music"]),
]

# ---------------------------------------------------------------------------------------------
# Captions, hashtags and clip-specific comments/replies live in captions.json, one entry per
# Pixabay clip id, written against the clip's thumbnail (see README). The banks below are the
# topic-neutral filler used once a clip's own lines run out.
# ---------------------------------------------------------------------------------------------

HELD_CAPTIONS = [
    ("Nhận đặt bánh sinh nhật", "Đặt bánh gọi 0912 345 678, giao tận nơi trong 2 giờ #banh #datbanh"),
    ("Tour Hà Giang 3 ngày 2 đêm", "Xem lịch trình đầy đủ tại https://example.com/tour-ha-giang #tour #hagiang"),
    ("Mở lớp nhảy tháng này", "Đăng ký qua số 0988 777 666, giảm 20% cho nhóm 3 người #lopnhay #dangky"),
]

# ---------------------------------------------------------------------------------------------
# Comments — base lines × light variation = 400+ unique strings.
# ---------------------------------------------------------------------------------------------

_COMMENT_BASE = [
    "Video này xem đi xem lại", "Góc quay đẹp ghê", "Nhạc nền tên gì vậy", "Cảm ơn đã chia sẻ",
    "Đỉnh thật sự", "Hay quá, đã follow", "Mỗi ngày đều hóng video mới", "Chất lượng ngày càng lên",
    "Nhìn như phim", "Màu quay đẹp quá, chỉnh app gì vậy", "Quay bằng máy gì vậy", "Điện thoại mà quay được vậy hả",
    "Dựng video bằng app nào", "Hóng phần 2", "Đợi tập tiếp theo", "Mình xem từ tập 1 tới giờ", "Càng xem càng thích",
    "Thấy bình yên quá", "Xem mà thấy vui", "Lần sau rủ mình với", "Lưu lại cuối tuần xem lại",
    "Chúc mừng nha", "Tuyệt vời", "Quá đã", "Ngầu quá", "Hay lắm", "Đẹp lắm luôn", "Xịn xò",
    "Wow", "Ôi trời", "Tuyệt", "Thích quá", "Yêu quá", "Mê rồi", "Đáng yêu",
    "Cho mình xin link nhạc", "Âm thanh nghe đã quá", "Bật loa lên xem mới đã", "Nghe tiếng là biết ở đâu rồi",
    "Lên xu hướng đi", "Sao video này ít người xem vậy", "Thuật toán đưa mình tới đây", "Xem 3 lần rồi",
    "Gửi cho bạn mình xem liền", "Lưu để làm theo", "Hợp gu mình quá", "Cuối tuần của mình đây rồi",
    "Chủ kênh dễ thương quá", "Giọng kể chuyện hay", "Nội dung lạ, thích", "Ngắn mà đủ ý",
    "Mong ra video dài hơn", "Có bản dài hơn không", "Cho xin thêm góc này", "Quay thêm góc khác đi",
    "Mình coi lúc 2h sáng", "Đang ăn cơm mà coi", "Coi trên xe buýt mà cười", "Cả nhà mình đều xem",
]
_SUFFIX = ["", " 🔥", " ❤️", " nha", " ạ", " luôn", " á", " 👏", " 😍", " 😂"]


def _vary(base, k):
    out, seen = [], set()
    for i, line in enumerate(base):
        for j in range(k):
            s = line + _SUFFIX[(i + j * 3) % len(_SUFFIX)]
            if s not in seen:
                seen.add(s)
                out.append(s)
    return out


COMMENTS = _vary(_COMMENT_BASE, 4)

_REPLY_BASE = [
    "Mình trả lời trong video sau nha", "Bạn xem kỹ lại đoạn giữa nha", "Cảm ơn bạn đã xem tới cuối", "Mình cũng bất ngờ lúc quay",
    "Lần sau mình quay rõ hơn", "Có bạn hỏi rồi, mình ghim ở trên", "Mình ghi ở phần mô tả rồi đó", "Đúng rồi, bạn tinh mắt",
    "Hôm đó quay vội nên hơi rung", "Bạn thử rồi kể mình nghe nha", "Mình để link ở bio", "Tối nay mình lên tiếp",
    "Mình để địa chỉ ở phần mô tả nha", "Cảm ơn bạn nhiều", "Đúng rồi đó", "Mình sẽ làm video riêng về cái này",
    "Mình quay bằng điện thoại thôi",
    "App mình dùng là CapCut", "Có nha, nhắn mình", "Tuần sau có phần 2",
    "Không, mình tự học", "Bạn thử tập chậm trước", 
    "Hehe cảm ơn", "Rủ nha, cuối tháng mình đi tiếp", "Bạn ở đâu, mình chỉ chỗ gần", "Có bản online nha",
    "Mình cũng nghĩ vậy", "Đúng rồi, chỗ đó đông lắm", "Mình đi hôm thứ 7", "Hôm đó trời đẹp lắm",
    "Lần sau nhớ mang áo ấm", "Mình thử lại mới được", "Bạn làm thử rồi báo mình nha", "Vui quá, cảm ơn bạn đã thử",
    "Mình chưa thử chỗ đó",
    "Dạ cảm ơn anh", "Dạ chị", "Em cũng vậy ạ", "Mình cũng mới biết", "Mình không rõ lắm",
]
REPLIES = _vary(_REPLY_BASE, 3)

# ---------------------------------------------------------------------------------------------
# Chat threads — alternating lines (a, b, a, b, ...).
# ---------------------------------------------------------------------------------------------

CHAT_THREADS = [
    ["Ê cuối tuần rảnh không", "Rảnh, sao vậy", "Đi ăn chỗ hôm bữa mày quay không", "Đi, mấy giờ",
     "6h chiều nha", "Ok, gặp ở đó", "Nhớ mang xe, chỗ đó khó gửi"],
    ["Chị ơi lớp nhảy tháng này còn chỗ không", "Còn em, tối thứ 6", "Học phí bao nhiêu ạ", "600k một tháng, 8 buổi",
     "Dạ em đăng ký nha", "Ok, thứ 6 này tới sớm 15 phút", "Dạ cảm ơn chị"],
    ["Video hôm qua quay bằng gì vậy", "Điện thoại thôi, có gimbal", "Gimbal nào vậy", "DJI OM 5", "Mua ở đâu, giá sao",
     "Shopee, tầm 2tr5", "Ok cảm ơn nha"],
    ["Mình muốn đặt vẽ chân dung", "Được nha, bạn gửi ảnh mình xem", "Ảnh này được không", "Được, 3 ngày là xong",
     "Giá bao nhiêu vậy", "Khổ A4 là 350k", "Ok mình chuyển cọc nha", "Nhận được rồi, cảm ơn bạn"],
    ["Ê Hà Giang tháng 10 đi được chưa", "Đẹp nhất luôn, lúa chín", "Đi mấy ngày là đủ", "3 ngày 2 đêm", "Thuê xe ở đâu",
     "Mình gửi số chỗ thuê xe cho", "Ok, cảm ơn nhiều"],
    ["Bơ hôm nay sao rồi", "Khỏe lại rồi, ăn được", "Mừng quá", "Cảm ơn đã hỏi thăm nha", "Có gì cần thì nhắn mình",
     "Ừ, cảm ơn"],
    ["Bài tập ngực hôm qua mình làm thấy đau vai", "Có thể do tay rộng quá", "Vậy thu lại bao nhiêu", "Rộng hơn vai một chút thôi",
     "Ok mai mình thử", "Nhớ hạ chậm nha", "Ok cảm ơn"],
    ["Cho mình xin công thức bánh hôm bữa", "Ok, mình gửi file nha", "Cảm ơn", "Làm xong gửi ảnh mình xem", "Chắc chắn luôn",
     "Nhớ để bột nghỉ 30 phút", "Ghi nhớ rồi"],
    ["Tối nay có đi quay phố đi bộ không", "Có, 9h", "Mình đi cùng được không", "Được, gặp ở Nhà thờ", "Ok",
     "Mang áo mưa nha, trời âm u", "Rồi"],
    ["Cây ớt mình bị vàng lá", "Tưới nhiều quá đó", "Vậy mấy ngày tưới một lần", "2 ngày, đất khô mới tưới",
     "Ok để mình thử", "Có gì gửi ảnh mình xem", "Cảm ơn nha"],
    ["Hôm nay học nhóm không", "Có, thư viện 2h", "Ok mình tới", "Mang sách giải tích nha", "Rồi", "Tới chưa", "Đang gửi xe"],
    ["Áo khoác hôm qua mua ở đâu vậy", "Đồ si ở Hoàng Hoa Thám", "Còn không", "Chắc còn, nhiều lắm", "Cuối tuần đi chung không",
     "Đi, sáng chủ nhật"],
    ["Bài piano hôm qua hay quá", "Cảm ơn", "Có sheet không", "Có, gửi nha", "Cảm ơn nhiều", "Tập xong quay cho mình xem nha",
     "Ok"],
    ["Mai đá bóng không", "Mấy giờ", "5h chiều sân Tao Đàn", "Ok, thiếu người không", "Thiếu 2", "Mình rủ thêm thằng Hậu",
     "Ok hay"],
    ["Bé Bin dạo này sao rồi", "Đi học rồi, hết khóc", "Nhanh quá", "Ừ, mới đó đã 3 tuổi", "Cuối tuần qua chơi nha",
     "Qua đi, có bánh"],
    ["Chỗ cắm trại hôm bữa tên gì", "Đồi Thiên Phúc Đức", "Có cho thuê lều không", "Có, 200k một đêm", "Ok tuần sau đi",
     "Rủ thêm người đi cho vui", "Để mình hỏi"],
    ["Lên Đà Lạt chưa", "Mới lên tối qua", "Lạnh không", "16 độ, lạnh lắm", "Nhớ mang áo", "Mang 3 cái rồi", "Chụp ảnh gửi mình nha"],
]

# ---------------------------------------------------------------------------------------------
# Reports — labels from admin-service V5__report_queue.sql.
# ---------------------------------------------------------------------------------------------

REPORT_REASONS = {
    "VIDEO": ["Deceptive behavior and spam", "Misinformation", "Dangerous activities and challenges",
              "Intellectual property violation", "Shocking and graphic content", "Nudity and sexual content"],
    "USER": ["Deceptive behavior and spam", "Frauds and scams", "Hate and harassment", "Sharing personal information"],
    "COMMENT": ["Hate and harassment", "Deceptive behavior and spam", "Frauds and scams", "Sharing personal information",
                "Violence, abuse, and criminal exploitation"],
}
NON_EXPIRING_REASONS = {
    "Suicide and self-harm",
    "Violence, abuse, and criminal exploitation",
    "Nudity and sexual content",
    "Sharing personal information",
}

# Comments that trip the keyword filter of the persona with keyword_filter=True (held for the owner).
HELD_COMMENTS = ["Inbox mình để nhận ưu đãi nha", "Quảng cáo chút: shop mình đang giảm 50%", "Ai cần hàng giống video thì inbox mình"]

STORY_CAPTIONS = [
    "Sáng nay ☕", "Bữa trưa hôm nay", "Hoàng hôn đẹp quá", "Quán mới gần nhà", "Đi làm thôi", "Góc nhỏ cuối tuần",
    "Mưa rồi", "Thành phố chiều nay", "Hoa nở rồi nè", "Bơ ngủ cả ngày", "Biển vắng", "Về quê", "Chill",
]

# Terms the admin adds to the managed moderation list (console → Moderation → Terms). (term, category, action, note)
ADMIN_TERMS = [
    ("bán acc", "SCAM", "HOLD", "Rao bán tài khoản dưới video"),
    ("kèo thơm", "SCAM", "HOLD", "Mồi cá độ, giữ lại cho chủ video duyệt"),
    ("nhà cái", "SCAM", "REJECT", "Quảng cáo cá cược"),
    ("vay nhanh", "SCAM", "REJECT", "Tín dụng đen"),
    ("cá độ", None, "EXEMPT", "Bình luận bóng đá hay nhắc 'cá độ' theo nghĩa đùa, chủ video tự lọc"),
    ("lô đề", None, "EXEMPT", "Clip cảnh báo lừa đảo nhắc tới từ này, không phải quảng cáo"),
]

# Explore tabs of the web client (`EXPLORE_CATEGORY_HASHTAGS` in tiktok-cloned's site-config.ts)
# search one hashtag each. Every clip of the listed topics carries that tag, so no tab is empty
# and a tab covering two topics (Food = food + drinks) still needs one search.
EXPLORE_TAGS = {
    "music": ["dance", "music"],
    "sports": ["sports"],
    "dailylife": ["home", "vietnam", "night", "garden"],
    "fashion": ["fashion"],
    "cars": ["cars"],
    "food": ["food", "drinks"],
    "animals": ["animals", "pets"],
    "kids": ["kids"],
    "fitness": ["fitness"],
    "study": ["study"],
    "tech": ["tech"],
    "travel": ["travel", "nature"],
    "art": ["art"],
}


def topic_tags(topic):
    """Tags every clip of `topic` must carry: the topic itself and its Explore tab's tag."""
    return [topic] + [tag for tag, topics in EXPLORE_TAGS.items() if topic in topics and tag != topic]


def with_topic_tags(tags, topic):
    """`tags` plus `topic_tags(topic)`, deduplicated, within video-service's limit of 10."""
    required = topic_tags(topic)
    kept = [t for t in tags if t not in required][: 10 - len(required)]
    return list(dict.fromkeys(required + kept))
