package com.bigq.demodogcat.saytheword

import com.bigq.demodogcat.R

data class WordItem(
    val image: Int,
    val label: String
)

// Level 1: 8 ảnh
val wordListLevel1 = listOf(
    WordItem(image = R.drawable.img_dog_1, label = "Chó"),
    WordItem(image = R.drawable.img_dog_2, label = "Chó"),
    WordItem(image = R.drawable.img_cat_1, label = "Mèo"),
    WordItem(image = R.drawable.img_cat_2, label = "Mèo"),
    WordItem(image = R.drawable.img_dog_2, label = "Chó"),
    WordItem(image = R.drawable.img_cat_3, label = "Mèo"),
    WordItem(image = R.drawable.img_cat_4, label = "Mèo"),
    WordItem(image = R.drawable.img_dog_4, label = "Chó"),
)

// Level 2: 8 ảnh (thứ tự khác)
val wordListLevel2 = listOf(
    WordItem(image = R.drawable.img_cat_1, label = "Mèo"),
    WordItem(image = R.drawable.img_dog_1, label = "Chó"),
    WordItem(image = R.drawable.img_dog_4, label = "Chó"),
    WordItem(image = R.drawable.img_cat_3, label = "Mèo"),
    WordItem(image = R.drawable.img_cat_2, label = "Mèo"),
    WordItem(image = R.drawable.img_dog_2, label = "Chó"),
    WordItem(image = R.drawable.img_dog_3, label = "Chó"),
    WordItem(image = R.drawable.img_cat_4, label = "Mèo"),
)

// Level 3: 8 ảnh (thứ tự khác)
val wordListLevel3 = listOf(
    WordItem(image = R.drawable.img_dog_3, label = "Chó"),
    WordItem(image = R.drawable.img_cat_4, label = "Mèo"),
    WordItem(image = R.drawable.img_dog_1, label = "Chó"),
    WordItem(image = R.drawable.img_cat_1, label = "Mèo"),
    WordItem(image = R.drawable.img_dog_4, label = "Chó"),
    WordItem(image = R.drawable.img_cat_2, label = "Mèo"),
    WordItem(image = R.drawable.img_cat_3, label = "Mèo"),
    WordItem(image = R.drawable.img_dog_2, label = "Chó"),
)

// Level 4: 8 ảnh (thứ tự khác)
val wordListLevel4 = listOf(
    WordItem(image = R.drawable.img_cat_2, label = "Mèo"),
    WordItem(image = R.drawable.img_cat_3, label = "Mèo"),
    WordItem(image = R.drawable.img_dog_3, label = "Chó"),
    WordItem(image = R.drawable.img_dog_1, label = "Chó"),
    WordItem(image = R.drawable.img_cat_1, label = "Mèo"),
    WordItem(image = R.drawable.img_dog_4, label = "Chó"),
    WordItem(image = R.drawable.img_dog_2, label = "Chó"),
    WordItem(image = R.drawable.img_cat_4, label = "Mèo"),
)

// Level 5: 8 ảnh (thứ tự khác)
val wordListLevel5 = listOf(
    WordItem(image = R.drawable.img_cat_4, label = "Mèo"),
    WordItem(image = R.drawable.img_dog_3, label = "Chó"),
    WordItem(image = R.drawable.img_cat_2, label = "Mèo"),
    WordItem(image = R.drawable.img_dog_4, label = "Chó"),
    WordItem(image = R.drawable.img_dog_1, label = "Chó"),
    WordItem(image = R.drawable.img_cat_3, label = "Mèo"),
    WordItem(image = R.drawable.img_dog_2, label = "Chó"),
    WordItem(image = R.drawable.img_cat_1, label = "Mèo"),
)

// Tất cả levels
val allLevels = listOf(
    wordListLevel1,
    wordListLevel2,
    wordListLevel3,
    wordListLevel4,
    wordListLevel5,
)

// Backward compatibility
val wordList = wordListLevel1