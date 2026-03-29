package de.stustapay.stustapay.ui.sale

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.ceil

/**
 * Tip selection screen shown between product selection and checkout.
 * Displayed upside-down (180°) so the cashier can show it to the customer.
 */
@Composable
fun SaleTipSelect(
    viewModel: SaleViewModel,
    onTipSelected: (tipCents: UInt) -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit,
) {
    val saleConfig by viewModel.saleConfig.collectAsStateWithLifecycle()
    val saleStatus by viewModel.saleStatus.collectAsStateWithLifecycle()

    // Calculate total price from selected items (same logic as SaleSelection bottom bar)
    var totalPrice = 0.0
    val config = saleConfig
    if (config is SaleConfig.Ready) {
        for (button in config.buttons) {
            val buttonStatus = saleStatus.buttonSelection[button.value.id] ?: continue
            when (buttonStatus) {
                is SaleItemAmount.FreePrice -> {
                    totalPrice += buttonStatus.price.toDouble() / 100.0
                }
                is SaleItemAmount.FixedPrice -> {
                    totalPrice += when (val price = button.value.price) {
                        is SaleItemPrice.FreePrice -> {
                            buttonStatus.amount * (price.defaultPrice ?: 0.0)
                        }
                        is SaleItemPrice.FixedPrice -> {
                            buttonStatus.amount * price.price
                        }
                        is SaleItemPrice.Returnable -> {
                            buttonStatus.amount * (price.price ?: 0.0)
                        }
                    }
                }
            }
        }
    }

    val tipOptions = listOf(
        TipOption(15, totalPrice),
        TipOption(10, totalPrice),
        TipOption(5, totalPrice),
    )

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Back button for cashier (not rotated, bottom-left from cashier perspective)
        TextButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
        ) {
            Text(
                text = "\u2190 Zurück",
                fontSize = 14.sp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
            )
        }

        // Entire tip area rotated 180° so customer can read it
        Box(
            modifier = Modifier
                .fillMaxSize()
                .rotate(180f),
            contentAlignment = Alignment.Center
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Total amount at top (from customer's perspective)
            Text(
                text = "Gesamt",
                fontSize = 20.sp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
            Text(
                text = "%.2f\u00A0\u20AC".format(totalPrice),
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "Trinkgeld?",
                fontSize = 24.sp,
                color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Top row: 15% and 10%
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TipButton(
                    option = tipOptions[0],
                    modifier = Modifier.weight(1f),
                    onClick = { onTipSelected(tipOptions[0].amountCents) }
                )
                TipButton(
                    option = tipOptions[1],
                    modifier = Modifier.weight(1f),
                    onClick = { onTipSelected(tipOptions[1].amountCents) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Bottom row: 5% and "Nein danke"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TipButton(
                    option = tipOptions[2],
                    modifier = Modifier.weight(1f),
                    onClick = { onTipSelected(tipOptions[2].amountCents) }
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(90.dp),
                    contentAlignment = Alignment.Center
                ) {
                    TextButton(
                        onClick = onSkip,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Text(
                            text = "Nein danke",
                            fontSize = 16.sp,
                            color = MaterialTheme.colors.onBackground.copy(alpha = 0.4f),
                        )
                    }
                }
            }
        }
        }
    }
}

data class TipOption(
    val percent: Int,
    val baseAmount: Double,
) {
    // Round up to nearest 10 cents
    val amount: Double = ceil(baseAmount * percent / 100.0 * 10.0) / 10.0
    val amountCents: UInt = (amount * 100).toUInt()
}

@Composable
fun TipButton(
    option: TipOption,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(90.dp),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = MaterialTheme.colors.primary,
            contentColor = MaterialTheme.colors.onPrimary
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "${option.percent}%",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "%.2f\u00A0\u20AC".format(option.amount),
                fontSize = 16.sp,
            )
        }
    }
}
