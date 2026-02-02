package com.coremotion.perik3.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.coremotion.perik3.R
import com.coremotion.perik3.ui.MeasurementLogRow

class MeasurementLogAdapter(
    private val context: Context,
    private val items: MutableList<MeasurementLogRow>
) : BaseAdapter() {

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): Any = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    fun submit(newItems: List<MeasurementLogRow>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_measurement_log_row, parent, false)

        val row = items[position]
        view.findViewById<TextView>(R.id.tvC1).text = row.c1
        view.findViewById<TextView>(R.id.tvC2).text = row.c2
        view.findViewById<TextView>(R.id.tvC3).text = row.c3
        view.findViewById<TextView>(R.id.tvC4).text = row.c4
        view.findViewById<TextView>(R.id.tvC5).text = row.c5
        return view
    }
}