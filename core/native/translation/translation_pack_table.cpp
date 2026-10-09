/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

#include "translation_pack_table.h"

namespace {
#define ORCHARD_HELSINKI(code, rev) "https://huggingface.co/Helsinki-NLP/opus-mt_tiny_" code "-eng/resolve/" rev "/", rev
// Packs converted by scripts/translation/build_packs.py, pinned to one commit of Orchard's repo.
#define ORCHARD_HOSTED(id) "https://huggingface.co/SFG545/orchard-lyric-translation/resolve/2eadc41be0669aa4b07d516e4a65c7bfdb26f22c/" id "/"
constexpr const char *kHelsinkiCredit = "OPUS-MT tiny by Helsinki-NLP (Apache-2.0)";

// Pinned commits plus hashes: a moved upstream file fails verification instead of loading.
constexpr TranslationPack kPacks[] = {
    {"kor-eng", "kor", "standard", ORCHARD_HELSINKI("kor", "755c263bce01f7b1aa9472b32940961fa1efccf3"),
     {{"model.tflite", 20144264, "87407b100e072f252cb67b7065c362d0daf3d2b69275b2bb516143e0b4fbb586"},
      {"source.spm", 819766, "b3306bd45c665df899441788138d4735094d22cf3027dd81d71bb4f36d012d3f"},
      {"vocab.json", 899018, "725cafb1962cf3abce71d2596543cbebed3029f044fe132cd6a50e6cf88245e2"}},
     kHelsinkiCredit},
    {"zho-eng", "zho", "standard", ORCHARD_HELSINKI("zho", "4ee3a9342aae4ca6fbae82c930b5dccff5dd68dd"),
     {{"model.tflite", 20144264, "dcca1b5f98af1787cf7047799bd446bffaadb04246d706fb19867555e3de5eeb"},
      {"source.spm", 755175, "e5528a5c3685d08f8868902d31febeb763eea8519f9997f43b05964b02554640"},
      {"vocab.json", 776356, "13690d04e27310a43f0d879dfd38823c317e9ac69f436754baffbb0da6fc9dbe"}},
     kHelsinkiCredit},
    {"rus-eng", "rus", "standard", ORCHARD_HELSINKI("rus", "795b0b2fb13d17b917287421c15e0d70db9ba940"),
     {{"model.tflite", 20144264, "9b44353a63ccdd53253949a9321a280d6fa7e355021062d225b0c7158cba0879"},
      {"source.spm", 956176, "e684e7edf0c2a134665ac393e09e342175c7f909549e7aa0cbf0ad27390729f1"},
      {"vocab.json", 1455694, "3dfeb79f213886598ff9dd61b1ac87f068f1f729f5a4536829c8468994f57eb4"}},
     kHelsinkiCredit},
    {"ara-eng", "ara", "standard", ORCHARD_HELSINKI("ara", "4f9a7d50764299154b8c09ca0a44e9835580b92b"),
     {{"model.tflite", 20144264, "12a8d433c040053d7bbf85eca7693816ab1e5fa2a5f580a251690ade77f53597"},
      {"source.spm", 873174, "a332c963a3670b24bd3486632f5e9d3f9fa9b447556a8c944180f58e786f6a73"},
      {"vocab.json", 1196381, "1d9083d56ef6aeb9428ffe549e813b287d907c7fca825e6de23f6b30faad0a4d"}},
     kHelsinkiCredit},
    {"ell-eng", "ell", "standard", ORCHARD_HELSINKI("ell", "beff0d92479a72deff22f722ab28efa81d2ac8a9"),
     {{"model.tflite", 20144264, "245bc09ba6942a12d13d44f04c94bbd7ecc96ba9cfa7a5108c82b2db61c55698"},
      {"source.spm", 930710, "0935c946038cf67e3aa01a137d8ed4d13504ec4066d87083377272a80ec36e1e"},
      {"vocab.json", 1370651, "df58d10d4d7e1ce0671deac147f2b45638e3c1d1cccbf8c438e7145081878a6c"}},
     kHelsinkiCredit},
    {"tur-eng", "tur", "standard", ORCHARD_HELSINKI("tur", "2deaeba77ed2b5e6d1ae2a27b6169b2db2d7f2cb"),
     {{"model.tflite", 20144264, "da9c7fc86d95296ebfc6ed44cc0122b45175a0fe625467b80f57213c28a93a6c"},
      {"source.spm", 828303, "514637f7fbe60aaf50758d3b57dae3a57ed683bd30b6996c719a7c1b8c813641"},
      {"vocab.json", 845381, "492e7b7158a842e1e7d5272253cac951cfafba81b92b78b78168d07722f66ca7"}},
     kHelsinkiCredit},
    {"fra-eng", "fra", "standard", ORCHARD_HELSINKI("fra", "2e635daccd920c96ead1e72aa7601595022317ee"),
     {{"model.tflite", 20144264, "e85621db9b85f8c1e6f946353d44c8df5355dd350fce5299e28546b71596a818"},
      {"source.spm", 817787, "878ba36f64bf4025f296526ce725521839008fb5b69566b10e859e8617601421"},
      {"vocab.json", 792743, "4370436d3bcb91ca7f00b402a905248b9eb7535051af8ba54b8ba533b56e9f6c"}},
     kHelsinkiCredit},
    {"deu-eng", "deu", "standard", ORCHARD_HELSINKI("deu", "d120fdfccd7ba4fadabdc8aba72b86acd473e2b2"),
     {{"model.tflite", 20144264, "00339c387b2b7f1c425e660f43b3b182df7694c659ece9018074fa031bb48189"},
      {"source.spm", 813654, "b0c0d8de2d7f020afbfe2c9bd86c60cafc340b3d0cf45b969ab13313c462e0fb"},
      {"vocab.json", 774615, "8983a5be7ba67df7e376c34a8041f65663f78517bbd270af0c9ad703e7169b1d"}},
     kHelsinkiCredit},
    {"spa-eng", "spa", "standard", ORCHARD_HELSINKI("spa", "ca15fbd0a8412a473b95e010ad54429a7a34a7ba"),
     {{"model.tflite", 20144264, "27f152f706a2efea296031a7962448a52cd61492467fe232f950851a0240077c"},
      {"source.spm", 819359, "a40ad94d67f431248d5dec9c7f0ded19efce671568ff07c959ee58ca50590265"},
      {"vocab.json", 788609, "a2efd26a550481379f0aafbf2b6252914fd20e537457a258f830d05dd7a8e2c2"}},
     kHelsinkiCredit},
    {"ita-eng", "ita", "standard", ORCHARD_HELSINKI("ita", "ac17655796ab9cd2f5916e3f454c9d09d9ea3a87"),
     {{"model.tflite", 20144264, "f8a9b69dfd4f5bcdbc889c2e9a71ee16d5b715e9c8a9738109db362fc8ce1bb1"},
      {"source.spm", 817363, "41026f577bfdf1b448d1383910a7878eee9b202202c972f8f13d7a8c4d1352d7"},
      {"vocab.json", 775633, "29025d02d70a44bb69283a58c7771648889d0431131aa36fc7b9d60eddaae165"}},
     kHelsinkiCredit},
    {"nld-eng", "nld", "standard", ORCHARD_HELSINKI("nld", "1d00eacd4a5d93382a60444a4c0f0d2ab46c9ddc"),
     {{"model.tflite", 20144264, "7677804d96828b5d89babfb996191b0330bc7c9fcdebaf741e3f08f2d7afc177"},
      {"source.spm", 810940, "9d0fb485b5d5fa554d8c30192a6a94ad729ed4bbfa3ff820b55eeec4e8ca8855"},
      {"vocab.json", 765325, "d9cb34092f3e7b04377b6429932a87347d9b1d9cda4ebdc34cdbadeb54a7379f"}},
     kHelsinkiCredit},
    {"cat-eng", "cat", "standard", ORCHARD_HELSINKI("cat", "a717a864a3452d0f6fdf8d9963e9d2741292ef1a"),
     {{"model.tflite", 20144264, "fddfeba8eb5ec3205b169133eea17ef89ad4538654ec648b7dcfd77f003350f5"},
      {"source.spm", 816709, "f39353ef7507738d1766fb4104ca93b316cc816497198c452433e991a1966971"},
      {"vocab.json", 784803, "6cd3ea3f3ecb3c7fda30aa2799984cbaa55f02ea0cb0f5a914c8e917249a3ad0"}},
     kHelsinkiCredit},
    // Hosted: Japanese standard plus every High pack.
    {"jpn-eng", "jpn", "standard", ORCHARD_HOSTED("jpn-eng"), "jpn-eng-8c76c40c85f2",
     {{"model.tflite", 17894496, "cacf76ce48c167089ca73c27796386831854f886b36d835ccaa761be9dd5feb6"},
      {"source.spm", 792751, "a6824c2996a4877cae8a0240f11a112ab0e4ea1f22e54cd42179376fe558f4ff"},
      {"vocab.json", 881911, "f508b75734ee21cf16b3816b7b29dea400f1f5998f47b7695cef1979ca49e106"}},
     "ElanMT tiny by the ELAN MITSUA Project (CC BY-SA 4.0), converted to LiteRT"},
    {"kor-eng-high", "kor", "high", ORCHARD_HOSTED("kor-eng-high"), "kor-eng-high-e42d1f41b661",
     {{"model.tflite", 82795568, "178dc2499009653100cd27803a65391788c25b17658ae91ec161d41ebaa7ee46"},
      {"source.spm", 841805, "c9496f7c2be9aecb84c751ae9f35a875915dde8e3892f652a5c76811ab2a0f49"},
      {"vocab.json", 1719866, "d42e3838e7ed0e43785fa013ef22ab4d382248cdf4e959c46a2b48db5eb019e9"}},
     "OPUS-MT by Helsinki-NLP (Apache-2.0), converted to LiteRT"},
    {"jpn-eng-high", "jpn", "high", ORCHARD_HOSTED("jpn-eng-high"), "jpn-eng-high-0770961a39ba",
     {{"model.tflite", 80430240, "2f878393e21710f6bba2b251148cb766e6634eaf58cf37c26d53dc88df632219"},
      {"source.spm", 781853, "d0b5c3b10b5959f056ff2c86e2f2356129242ff1fc72d3a4a34d6a8c0eee4e57"},
      {"vocab.json", 1501341, "0483c9c2b3deade6d6fbfe149eccf9cd3c44c77b348232030ae2d81400d65b61"}},
     "OPUS-MT by Helsinki-NLP (Apache-2.0), converted to LiteRT"},
    {"zho-eng-high", "zho", "high", ORCHARD_HOSTED("zho-eng-high"), "zho-eng-high-cf109095479d",
     {{"model.tflite", 82795568, "b1f454f511769ba3b69f2b398717f825756dff2820b5ccd9ab3cf5a94b475c6b"},
      {"source.spm", 804677, "e27a3a1b539f4959ec72ea60e453f49156289f95d4e6000b29332efc45616203"},
      {"vocab.json", 1617902, "c0f79ee4c413ccb24cd808a0c727eec85b2130f23c82802cc1caeb07b5f0d458"}},
     "OPUS-MT by Helsinki-NLP (CC BY 4.0), converted to LiteRT"},
    {"rus-eng-high", "rus", "high", ORCHARD_HOSTED("rus-eng-high"), "rus-eng-high-fbd6dc73284f",
     {{"model.tflite", 81424960, "cd4dbee74885b318278cbeb3cc327843d159de12042457f6c70185311c80d1d1"},
      {"source.spm", 1080169, "745998e51ba5b058e38b7ac7765c25c43ed5c1c39cc92b27163b9b2e323c9d7c"},
      {"vocab.json", 2601758, "33e95da3be3fa3b50169c4c46693ba2f29fbf4cb29d99044bd07d72d181fa1e9"}},
     "OPUS-MT by Helsinki-NLP (CC BY 4.0), converted to LiteRT"},
    {"ara-eng-high", "ara", "high", ORCHARD_HOSTED("ara-eng-high"), "ara-eng-high-c5b2a50db78d",
     {{"model.tflite", 81599392, "cbbcb7f2a8c2e45b863400dafff4f50e78aa33aeace0e95deedd77abcc65d462"},
      {"source.spm", 917407, "484f7210e5f4466f7e5b99b660717c6ef2a6f90746196daba507795b69dc02a2"},
      {"vocab.json", 2131576, "86c1be5815ed18525f1cff0d0e08bcd73e95f25f9f924ea7552113d005560b7c"}},
     "OPUS-MT by Helsinki-NLP (Apache-2.0), converted to LiteRT"},
    {"ell-eng-high", "ell", "high", ORCHARD_HOSTED("ell-eng-high"), "ell-eng-high-b6bc9488a194",
     {{"model.tflite", 59237312, "fda9802934a06dec779fff3b76bacb2b1fe11bcc299ed0cb85286a2c41e93659"},
      {"source.spm", 507029, "534be973a18f057dd851e5d848b5e83467a5018f2c77f55beef714c2cc7b933f"},
      {"vocab.json", 801923, "5fa1d413628da6f2fdd8f358864c2d43014614c8e14521462497478cd7aeec39"}},
     "OPUS-MT by Helsinki-NLP (Apache-2.0), converted to LiteRT"},
    {"tur-eng-high", "tur", "high", ORCHARD_HOSTED("tur-eng-high"), "tur-eng-high-19c65427cc2a",
     {{"model.tflite", 81353744, "ab20b1e95a005f34dc6ef044ab61a3a8b7725edaa614ef6be953cb2887f5e38d"},
      {"source.spm", 839750, "4a6356e3ee892f2896dbf0b5cabeb51fdf16e03c70b0702cefc8b847e504241c"},
      {"vocab.json", 1563964, "f6518b18a71fea3de99b5883cb80bcd188b74c33e7c6a5a597b7c64119f1b1fe"}},
     "OPUS-MT by Helsinki-NLP (Apache-2.0), converted to LiteRT"},
    {"fra-eng-high", "fra", "high", ORCHARD_HOSTED("fra-eng-high"), "fra-eng-high-c4aed37b318c",
     {{"model.tflite", 79766752, "452541593936693f3174043dc9aad7cc0c8bbc3810b31f16753b55631242924e"},
      {"source.spm", 802397, "78d0e717c77053f1c4b856d8661d9cb87c64f083a35418c087b9146300e4f585"},
      {"vocab.json", 1339166, "945c604346ce15ce4aff9001001e7f925e336d942c4087017f191871162cbdc4"}},
     "OPUS-MT by Helsinki-NLP (Apache-2.0), converted to LiteRT"},
    {"deu-eng-high", "deu", "high", ORCHARD_HOSTED("deu-eng-high"), "deu-eng-high-1a922f3b32a8",
     {{"model.tflite", 78986768, "8c1434d69c536157ecf2cf686dd0ed95b019e75e404c2bbd1bb4c8f1d46f9f0b"},
      {"source.spm", 796845, "bbd1f495eea99c8e21ae086d9146e0fa7b096c3dfdd9ba07ab8b631889df5c9b"},
      {"vocab.json", 1273232, "0d70d89fee4a8b4ef99a56d712163baadcabd5600a597f71515547ee70306329"}},
     "OPUS-MT by Helsinki-NLP (Apache-2.0), converted to LiteRT"},
    {"spa-eng-high", "spa", "high", ORCHARD_HOSTED("spa-eng-high"), "spa-eng-high-c96e2c5399eb",
     {{"model.tflite", 82795568, "a4144122f0281a82fc743290b19f0eb0af661b285f57de63ccaa31716f2c4728"},
      {"source.spm", 825924, "e236ee6d866b635c0142114f8647f39831f9d92534aa2aad75c942f6a78ad0e3"},
      {"vocab.json", 1590040, "257f346d7a6b2ecceafcca8ba05648ce2fd68dfaf105fb0e913dca7198f3f6d5"}},
     "OPUS-MT by Helsinki-NLP (Apache-2.0), converted to LiteRT"},
    {"ita-eng-high", "ita", "high", ORCHARD_HOSTED("ita-eng-high"), "ita-eng-high-42556a0848fc",
     {{"model.tflite", 91284224, "a9b83ec008444f4e094936194632b3f4da98a9d53a7d0326428ca052cf9f664b"},
      {"source.spm", 813709, "1bd307e2756991d470e4b40172147f7889b46a323b1a9a2150e66274825b1f21"},
      {"vocab.json", 2369833, "f4ea61764cde265f25c2824bfe5b945685526ede81c5f2f76715a5652a63ccdb"}},
     "OPUS-MT by Helsinki-NLP (Apache-2.0), converted to LiteRT"},
    {"nld-eng-high", "nld", "high", ORCHARD_HOSTED("nld-eng-high"), "nld-eng-high-48af999f2c59",
     {{"model.tflite", 83914464, "dfbdb714843506c4e72e7cc541a5e214aaa93c0874aaf6a5ef0c6d068e4d823b"},
      {"source.spm", 813866, "f538d23d4b30641cd97c608d2a83890d503aa1286a5560f19ef949404a786e94"},
      {"vocab.json", 1660216, "52ef60fa185ed5a6a6e75a4ea45de99582f1c1493df1c072a784489697407b2b"}},
     "OPUS-MT by Helsinki-NLP (Apache-2.0), converted to LiteRT"},
    {"cat-eng-high", "cat", "high", ORCHARD_HOSTED("cat-eng-high"), "cat-eng-high-b16de52276f0",
     {{"model.tflite", 77415776, "742c710976d516ea11e5e94b24d66077450a73b8592f8ddd1153b3196c50db5d"},
      {"source.spm", 814870, "409f03510e6350f2988dc008104cd7a441111c4987e965c9263f708621bf6e48"},
      {"vocab.json", 1255491, "c1a122f91db8aeecf7c290ddb0d13abc673e61eb441f53ef287d3d424e0515df"}},
     "OPUS-MT by Helsinki-NLP (Apache-2.0), converted to LiteRT"},
};
#undef ORCHARD_HOSTED
#undef ORCHARD_HELSINKI
} // namespace

std::span<const TranslationPack> translationPackTable() { return kPacks; }
