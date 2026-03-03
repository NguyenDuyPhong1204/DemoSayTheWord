package com.bigq.demodogcat.saytheword

import com.bigq.demodogcat.R

data class WordItem(
    val image: Int,
    val label: String
)

val wordList = listOf(
    WordItem(
        image = R.drawable.img_dog_1,
        label = "Chó"
    ),
    WordItem(
        image = R.drawable.img_dog_2,
        label = "Chó"
    ),
    WordItem(
        image = R.drawable.img_cat_1,
        label = "Mèo"
    ),
    WordItem(
        image = R.drawable.img_cat_2,
        label = "Mèo"
    ),
    WordItem(
        image = R.drawable.img_dog_2,
        label = "Chó"
    ),
    WordItem(
        image = R.drawable.img_cat_3,
        label = "Mèo"
    ),
    WordItem(
        image = R.drawable.img_cat_4,
        label = "Mèo"
    ),
    WordItem(
        image = R.drawable.img_dog_4,
        label = "Chó"
    ),
)